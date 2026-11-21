package com.aster.vrmfacetracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var faceState: TextView
    private lateinit var trackingValues: TextView
    private lateinit var vrmWebView: WebView
    private lateinit var cameraExecutor: ExecutorService
    private var faceLandmarker: FaceLandmarker? = null
    private var lastUiUpdate = 0L
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback != null) {
            val uri = if (result.resultCode == RESULT_OK) result.data?.data else null
            callback.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else statusText.text = "Camera permission denied"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.cameraPreview)
        statusText = findViewById(R.id.statusText)
        faceState = findViewById(R.id.faceState)
        trackingValues = findViewById(R.id.trackingValues)
        vrmWebView = findViewById(R.id.vrmWebView)
        setupVrmPreview()
        cameraExecutor = Executors.newSingleThreadExecutor()
        try {
            setupFaceLandmarker()
        } catch (error: Exception) {
            statusText.text = "Face model setup failed: ${error.message}"
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun setupVrmPreview() {
        vrmWebView.settings.javaScriptEnabled = true
        vrmWebView.settings.domStorageEnabled = true
        vrmWebView.settings.allowFileAccess = true
        vrmWebView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(webView: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                filePicker.launch(Intent.createChooser(intent, "Select VRM model"))
                return true
            }
        }
        vrmWebView.webViewClient = WebViewClient()
        vrmWebView.loadDataWithBaseURL("https://appassets.androidplatform.net/assets/", VRM_HTML, "text/html", "UTF-8", null)
    }

    private fun setupFaceLandmarker() {
        val base = BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base).setRunningMode(RunningMode.VIDEO).setNumFaces(1)
            .setOutputFaceBlendshapes(true).build()
        faceLandmarker = FaceLandmarker.createFromOptions(this, options)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
            analysis.setAnalyzer(cameraExecutor) { analyzeFrame(it) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                statusText.text = "Camera active · looking for a face"
            } catch (error: Exception) {
                statusText.text = "Camera start failed: ${error.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        var source: Bitmap? = null
        var rotated: Bitmap? = null
        try {
            source = proxy.toBitmap()
            val matrix = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
            rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            val result = faceLandmarker?.detectForVideo(BitmapImageBuilder(rotated).build(), SystemClock.uptimeMillis())
            if (result != null) updateTrackingUi(result)
        } catch (error: Exception) {
            runOnUiThread { statusText.text = "Frame error: ${error.message}" }
        } finally {
            if (rotated != null && rotated !== source) rotated.recycle()
            source?.recycle()
            proxy.close()
        }
    }

    private fun updateTrackingUi(result: FaceLandmarkerResult) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUiUpdate < 70) return
        lastUiUpdate = now
        val categories = result.faceBlendshapes().orElse(emptyList()).firstOrNull()
        val values = categories?.associate { it.categoryName() to it.score() } ?: emptyMap()
        fun s(key: String) = values[key] ?: 0f
        val blinkL = s("eyeBlinkLeft")
        val blinkR = s("eyeBlinkRight")
        val jaw = s("jawOpen")
        val smile = maxOf(s("mouthSmileLeft"), s("mouthSmileRight"))
        val brow = maxOf(s("browInnerUp"), s("browOuterUpLeft"), s("browOuterUpRight"))
        val landmarks = result.faceLandmarks().firstOrNull()
        var yaw = 0f; var pitch = 0f; var roll = 0f
        if (landmarks != null && landmarks.size > 263) {
            val le = landmarks[33]; val re = landmarks[263]; val nose = landmarks[1]
            val midX = (le.x() + re.x()) / 2f; val midY = (le.y() + re.y()) / 2f
            val eyeW = kotlin.math.max(kotlin.math.abs(re.x() - le.x()), 0.001f)
            yaw = (((nose.x() - midX) / eyeW) * 1.6f).coerceIn(-0.65f, 0.65f)
            pitch = (((nose.y() - midY) / eyeW - 0.45f) * 1.2f).coerceIn(-0.55f, 0.55f)
            roll = kotlin.math.atan2(re.y() - le.y(), re.x() - le.x()).coerceIn(-0.7f, 0.7f)
        }
        val detected = result.faceLandmarks().isNotEmpty()
        val data = JSONObject().apply {
            put("detected", detected); put("blinkLeft", blinkL); put("blinkRight", blinkR)
            put("jawOpen", jaw); put("smile", smile); put("brow", brow)
            put("yaw", yaw); put("pitch", pitch); put("roll", roll)
        }
        runOnUiThread {
            faceState.text = if (detected) "Face: detected" else "Face: not detected"
            trackingValues.text = String.format(Locale.US,
                "Blink L/R: %.2f / %.2f\nMouth open: %.2f · Smile: %.2f\nBrows raised: %.2f\nHead yaw/pitch/roll: %.2f / %.2f / %.2f",
                blinkL, blinkR, jaw, smile, brow, yaw, pitch, roll)
            statusText.text = "Live tracking · MediaPipe Face Landmarker"
            vrmWebView.evaluateJavascript("window.updateFaceTracking && window.updateFaceTracking($data);", null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        faceLandmarker?.close()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        vrmWebView.destroy()
    }

    companion object {
        private val VRM_HTML = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>*{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;overflow:hidden;font-family:Arial;background:#eaf3ff;color:#24466d}#stage{position:absolute;inset:0}#bar{position:absolute;z-index:2;top:9px;left:9px;right:9px;display:flex;gap:8px;align-items:center}button{border:0;border-radius:10px;background:#477fb8;color:white;padding:10px 14px;font-weight:600}#file{display:none}#hint,#status{font-size:11px;background:#ffffffdf;padding:8px;border-radius:8px}#status{position:absolute;z-index:2;bottom:8px;left:9px;right:9px}</style>
<script type="importmap">{"imports":{"three":"https://cdn.jsdelivr.net/npm/three@0.170.0/build/three.module.js","three/addons/":"https://cdn.jsdelivr.net/npm/three@0.170.0/examples/jsm/","@pixiv/three-vrm":"https://cdn.jsdelivr.net/npm/@pixiv/three-vrm@3.2.0/lib/three-vrm.module.js"}}</script></head>
<body><div id="stage"></div><div id="bar"><button id="choose">Load VRM</button><input id="file" type="file" accept=".vrm,model/gltf-binary"><span id="hint">VRM 0.x / 1.0</span></div><div id="status">Starting 3D preview…</div>
<script type="module">
import * as THREE from 'three';
import {GLTFLoader} from 'three/addons/loaders/GLTFLoader.js';
import {VRMLoaderPlugin,VRMUtils} from '@pixiv/three-vrm';
const stage=document.getElementById('stage'),status=document.getElementById('status'),input=document.getElementById('file');
let renderer,scene,camera,vrm,last=0;
const say=t=>status.textContent=t;
try{
 renderer=new THREE.WebGLRenderer({antialias:true,alpha:true});renderer.setPixelRatio(Math.min(devicePixelRatio,1.6));renderer.setSize(stage.clientWidth,stage.clientHeight);renderer.outputColorSpace=THREE.SRGBColorSpace;stage.appendChild(renderer.domElement);
 scene=new THREE.Scene();camera=new THREE.PerspectiveCamera(30,stage.clientWidth/stage.clientHeight,.1,100);camera.position.set(0,1.35,3.2);
 scene.add(new THREE.HemisphereLight(0xffffff,0x9ab3d1,2));const key=new THREE.DirectionalLight(0xffffff,2);key.position.set(1,2,3);scene.add(key);
 const loader=new GLTFLoader();loader.register(p=>new VRMLoaderPlugin(p));
 document.getElementById('choose').onclick=()=>input.click();
 input.onchange=async()=>{const file=input.files&&input.files[0];if(!file)return;say('Loading '+file.name+'…');
  try{const url=URL.createObjectURL(file);const gltf=await loader.loadAsync(url);URL.revokeObjectURL(url);const next=gltf.userData.vrm;if(!next)throw new Error('No VRM extension found');
   if(vrm){scene.remove(vrm.scene);VRMUtils.deepDispose(vrm.scene)}vrm=next;VRMUtils.rotateVRM0(vrm);scene.add(vrm.scene);
   const box=new THREE.Box3().setFromObject(vrm.scene),size=box.getSize(new THREE.Vector3()),center=box.getCenter(new THREE.Vector3());vrm.scene.position.x-=center.x;vrm.scene.position.y-=box.min.y;vrm.scene.position.z-=center.z;
   const h=Math.max(size.y,1.2);camera.position.set(0,h*.62,h*2.5);camera.lookAt(0,h*.52,0);say('Loaded: '+file.name+' · Tracking connected');
  }catch(e){say('VRM load failed: '+(e.message||e))}
 };
 function loop(t){requestAnimationFrame(loop);const dt=last?(t-last)/1000:0;last=t;if(vrm)vrm.update(dt);renderer.render(scene,camera)}loop(0);
 new ResizeObserver(()=>{const w=stage.clientWidth,h=stage.clientHeight;camera.aspect=w/h;camera.updateProjectionMatrix();renderer.setSize(w,h)}).observe(stage);say('3D ready · Load a VRM model');
}catch(e){say('3D preview error: '+(e.message||e))}
window.updateFaceTracking=v=>{if(!vrm||!vrm.expressionManager)return;const em=vrm.expressionManager;const put=(n,x)=>{try{em.setValue(n,Math.max(0,Math.min(1,x||0)))}catch(_){}};
 put('blinkLeft',v.blinkLeft);put('blinkRight',v.blinkRight);put('aa',v.jawOpen);put('happy',v.smile);put('surprised',v.brow*.65);
 const head=vrm.humanoid&&vrm.humanoid.getNormalizedBoneNode('head');if(head){head.rotation.y=-v.yaw*.75;head.rotation.x=v.pitch*.55;head.rotation.z=-v.roll}
};
</script></body></html>
        """.trimIndent()
    }
}
