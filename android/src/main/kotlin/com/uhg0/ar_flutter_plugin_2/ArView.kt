package com.uhg0.ar_flutter_plugin_2

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import com.google.ar.core.Anchor.CloudAnchorState
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.uhg0.ar_flutter_plugin_2.Serialization.deserializeMatrix4
import com.uhg0.ar_flutter_plugin_2.Serialization.serializeHitResult
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.canHostCloudAnchor
import io.github.sceneview.ar.arcore.fps
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.ar.node.CloudAnchorNode
import io.github.sceneview.ar.node.HitResultNode
import io.github.sceneview.gesture.MoveGestureDetector
import io.github.sceneview.gesture.RotateGestureDetector
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.sceneview.math.Position as ScenePosition
import io.github.sceneview.math.Rotation as SceneRotation
import io.github.sceneview.math.Scale as SceneScale
import io.github.sceneview.texture.ImageTexture
import io.github.sceneview.material.setTexture
import io.github.sceneview.ar.scene.PlaneRenderer
import io.flutter.FlutterInjector
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.colorOf
import io.github.sceneview.loaders.MaterialLoader
import com.google.ar.core.exceptions.SessionPausedException

class ArView(
    context: Context,
    private val activity: Activity,
    private val lifecycle: Lifecycle,
    messenger: BinaryMessenger,
    id: Int,
) : PlatformView {
    private val TAG: String = ArView::class.java.name
    private val viewContext: Context = context
    private var sceneView: ARSceneView
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private var worldOriginNode: Node? = null

    private val rootLayout: ViewGroup = FrameLayout(context)

    private val sessionChannel: MethodChannel = MethodChannel(messenger, "arsession_$id")
    private val objectChannel: MethodChannel = MethodChannel(messenger, "arobjects_$id")
    private val anchorChannel: MethodChannel = MethodChannel(messenger, "aranchors_$id")
    private val nodesMap = mutableMapOf<String, ModelNode>()
    private var planeCount = 0
    private var selectedNode: Node? = null
    private val detectedPlanes = mutableSetOf<Plane>()
    private val anchorNodesMap = mutableMapOf<String, AnchorNode>()
    private var showAnimatedGuide = true
    private var showFeaturePoints = false
    private val pointCloudNodes = mutableListOf<PointCloudNode>()
    private var lastPointCloudTimestamp: Long? = null
    private var lastPointCloudFrame: Frame? = null
    private var pointCloudModelInstances = mutableListOf<ModelInstance>()
    private var handlePans = false  
    private var handleRotation = false
    private var isSessionPaused = false

    private class PointCloudNode(
        modelInstance: ModelInstance,
        var id: Int,
        var confidence: Float,
    ) : ModelNode(modelInstance)

    private val onSessionMethodCall =
        MethodChannel.MethodCallHandler { call, result ->
            when (call.method) {
                "init" -> handleInit(call, result)
                "showPlanes" -> handleShowPlanes(call, result)
                "dispose" -> dispose()
                "getAnchorPose" -> handleGetAnchorPose(call, result)
                "getCameraPose" -> handleGetCameraPose(result)
                "snapshot" -> handleSnapshot(result)
                "disableCamera" -> handleDisableCamera(result)
                "enableCamera" -> handleEnableCamera(result)
                else -> result.notImplemented()
            }
        }
    private fun handleDisableCamera(result: MethodChannel.Result) {
        try {
            isSessionPaused = true
            sceneView.session?.pause()
            result.success(null)
        } catch (e: Exception) {
            result.error("DISABLE_CAMERA_ERROR", e.message, null)
        }
    }
    private fun handleEnableCamera(result: MethodChannel.Result) {
        try {
            isSessionPaused = false
            sceneView.session?.resume()
            result.success(null)
        } catch (e: Exception) {
            result.error("ENABLE_CAMERA_ERROR", e.message, null)
        }
    }
    private val onObjectMethodCall =
        MethodChannel.MethodCallHandler { call, result ->
            when (call.method) {
                "addNode" -> {
                    val nodeData = call.arguments as? Map<String, Any>
                    nodeData?.let {
                        handleAddNode(it, result)
                    } ?: result.error("INVALID_ARGUMENTS", "Node data is required", null)
                }
                "addNodeToPlaneAnchor" -> handleAddNodeToPlaneAnchor(call, result)
                "addNodeToScreenPosition" -> handleAddNodeToScreenPosition(call, result)
                "removeNode" -> {
                    handleRemoveNode(call, result)
                }
                "transformationChanged" -> {
                    handleTransformNode(call, result)
                }
                else -> result.notImplemented()
            }
        }

    private val onAnchorMethodCall =
        MethodChannel.MethodCallHandler { call, result ->
            when (call.method) {
                "addAnchor" -> handleAddAnchor(call, result)
                "removeAnchor" -> {
                    val anchorName = call.argument<String>("name")
                    handleRemoveAnchor(anchorName, result)
                }
                "initGoogleCloudAnchorMode" -> handleInitGoogleCloudAnchorMode(result)
                "uploadAnchor" -> handleUploadAnchor(call, result)
                "downloadAnchor" -> handleDownloadAnchor(call, result)
                else -> result.notImplemented()
            }
        }

    init {
        sceneView = ARSceneView(
            context = viewContext,
            sharedLifecycle = lifecycle,
            sessionConfiguration = { session, config ->
                config.apply {
                    depthMode = Config.DepthMode.DISABLED
                    instantPlacementMode = Config.InstantPlacementMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    focusMode = Config.FocusMode.AUTO
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                }
            }
        )
        
        rootLayout.addView(sceneView)

        sessionChannel.setMethodCallHandler(onSessionMethodCall)
        objectChannel.setMethodCallHandler(onObjectMethodCall)
        anchorChannel.setMethodCallHandler(onAnchorMethodCall)
    }

    

    private suspend fun buildModelNode(nodeData: Map<String, Any>): ModelNode? {
        var fileLocation = nodeData["uri"] as? String ?: return null
        when (nodeData["type"] as Int) {
                0 -> { // GLTF2 Model from Flutter asset folder
                    // Get path to given Flutter asset
                    val loader = FlutterInjector.instance().flutterLoader()
                    fileLocation = loader.getLookupKeyForAsset(fileLocation)
                }
                1 -> { // GLB Model from the web
                    fileLocation = fileLocation
                }
                2 -> { // fileSystemAppFolderGLB
                    fileLocation = fileLocation
                }
                 3 -> { //fileSystemAppFolderGLTF2
                    val documentsPath = viewContext.getApplicationInfo().dataDir
                    val fileLocation = documentsPath + "/app_flutter/" + nodeData["uri"] as String
                 }
                else -> {
                    return null
                }
        }
        
        if (fileLocation == null) {
            return null
        }
        val transformation = nodeData["transformation"] as? ArrayList<Double>
        if (transformation == null) {
            return null
        }

        return try {
            sceneView.modelLoader.loadModelInstance(fileLocation)?.let { modelInstance ->
                object : ModelNode(
                    modelInstance = modelInstance,
                    scaleToUnits = transformation.first().toFloat(),
                ) {
                    override fun onMove(detector: MoveGestureDetector, e: MotionEvent): Boolean {
                            if (handlePans) {
                            val defaultResult = super.onMove(detector, e)
                            objectChannel.invokeMethod("onPanChange", name)
                            return defaultResult
                            }
                    return false
                    }
                    
                    override fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent): Boolean {
                        if (handlePans) {
                            val defaultResult = super.onMoveBegin(detector, e)
                            objectChannel.invokeMethod("onPanStart", name)
                            defaultResult
                        } 
                        return false
                    }
                    
                    override fun onMoveEnd(detector: MoveGestureDetector, e: MotionEvent) {
                        if (handlePans) {
                            super.onMoveEnd(detector, e)
                            val transformMap = mapOf(
                                "name" to name,
                                "transform" to transform.toFloatArray().toList()
                            )
                            objectChannel.invokeMethod("onPanEnd", transformMap)
                        }
                    }

                    override fun onRotateBegin(detector: RotateGestureDetector, e: MotionEvent): Boolean {
                        if (handleRotation) {
                            val defaultResult = super.onRotateBegin(detector, e)
                            objectChannel.invokeMethod("onRotationStart", name)
                            return defaultResult
                        }
                        return false
                    }

                    override fun onRotate(detector: RotateGestureDetector, e: MotionEvent): Boolean {
                        if (handleRotation) {
                            val defaultResult = super.onRotate(detector, e)
                            objectChannel.invokeMethod("onRotationChange", name)
                            return defaultResult
                        }
                        return false
                    }

                    override fun onRotateEnd(detector: RotateGestureDetector, e: MotionEvent) {
                        if (handleRotation) {
                            super.onRotateEnd(detector, e)
                            val transformMap = mapOf(
                                "name" to name,
                                "transform" to transform.toFloatArray().toList()
                            )
                            objectChannel.invokeMethod("onRotationEnd", transformMap)
                        }
                    }
                }.apply {
                    isPositionEditable = handlePans
                    isRotationEditable = handleRotation
                    name = nodeData["name"] as? String
                }
            } ?: run {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun handleAddNodeToPlaneAnchor(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        try {
            val nodeData = call.arguments as? Map<String, Any>
            val dict_node = nodeData?.get("node") as? Map<String, Any>
            val dict_anchor = nodeData?.get("anchor") as? Map<String, Any>
            if (dict_node == null || dict_anchor == null) {
                result.success(false)
                return
            }

            val anchorName = dict_anchor["name"] as? String
            val anchorNode = anchorNodesMap[anchorName]
            if (anchorNode != null) {
                mainScope.launch {
                    try {
                        buildModelNode(dict_node)?.let { node ->
                            anchorNode.addChildNode(node)
                            sceneView.addChildNode(anchorNode)
                            node.name?.let { nodeName ->
                                nodesMap[nodeName] = node
                            }
                            result.success(true)
                        } ?: result.success(false)
                    } catch (e: Exception) {
                        result.success(false)
                    }
                }
            } else {
                result.success(false)
            }
        } catch (e: Exception) {
            result.success(false)
        }
    }

    private fun handleAddNodeToScreenPosition(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        try {
            val nodeData = call.arguments as? Map<String, Any>
            val screenPosition = call.argument<Map<String, Double>>("screenPosition")

            if (nodeData == null || screenPosition == null) {
                result.error("INVALID_ARGUMENT", "Node data or screen position is null", null)
                return
            }

            mainScope.launch {
                val node = buildModelNode(nodeData) ?: return@launch
                val hitResultNode =
                    HitResultNode(
                        engine = sceneView.engine,
                        xPx = screenPosition["x"]?.toFloat() ?: 0f,
                        yPx = screenPosition["y"]?.toFloat() ?: 0f,
                    ).apply {
                        addChildNode(node)
                    }

                sceneView.addChildNode(hitResultNode)
                result.success(null)
            }
        } catch (e: Exception) {
            result.error("ADD_NODE_TO_SCREEN_ERROR", e.message, null)
        }
    }

    private fun handleInit(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        try {
            val argShowAnimatedGuide = call.argument<Boolean>("showAnimatedGuide") ?: true
            val argShowFeaturePoints = call.argument<Boolean>("showFeaturePoints") ?: false
            val argPlaneDetectionConfig: Int? = call.argument<Int>("planeDetectionConfig")
            val argShowPlanes = call.argument<Boolean>("showPlanes") ?: true
            val customPlaneTexturePath = call.argument<String>("customPlaneTexturePath")
            val showWorldOrigin = call.argument<Boolean>("showWorldOrigin") ?: false
            val handleTaps = call.argument<Boolean>("handleTaps") ?: true
            handlePans = call.argument<Boolean>("handlePans") ?: false
            handleRotation = call.argument<Boolean>("handleRotation") ?: false

            sceneView.session?.let { session ->
                session.configure(session.config.apply {
                    depthMode = when (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        true -> Config.DepthMode.AUTOMATIC
                        else -> Config.DepthMode.DISABLED
                    }
                    planeFindingMode = when (argPlaneDetectionConfig) {
                        1 -> Config.PlaneFindingMode.HORIZONTAL
                        2 -> Config.PlaneFindingMode.VERTICAL
                        3 -> Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        else -> Config.PlaneFindingMode.DISABLED
                    }
                })
            }

            handleShowWorldOrigin(showWorldOrigin)
            
            sceneView.apply {
                environment = environmentLoader.createHDREnvironment(
                    assetFileLocation = "environments/evening_meadow_2k.hdr"
                )!!

                planeRenderer.isEnabled = argShowPlanes
                planeRenderer.isVisible = argShowPlanes
                planeRenderer.planeRendererMode = PlaneRenderer.PlaneRendererMode.RENDER_ALL

                onTrackingFailureChanged = { reason ->
                    mainScope.launch {
                        sessionChannel.invokeMethod("onTrackingFailure", reason?.name)
                    }
                }

                if (argShowFeaturePoints == true) {
                    showFeaturePoints = true
                } else {
                    showFeaturePoints = false
                    pointCloudNodes.toList().forEach { removePointCloudNode(it) }
                }

                onFrame = { frameTime ->
                    try {
                        if (!isSessionPaused) {
                            session?.update()?.let { frame ->
                                if (showAnimatedGuide) {
                                    frame.getUpdatedTrackables(Plane::class.java).forEach { plane ->
                                        if (plane.trackingState == TrackingState.TRACKING) {
                                            rootLayout.findViewWithTag<View>("hand_motion_layout")?.let { handMotionLayout ->
                                                rootLayout.removeView(handMotionLayout)
                                                showAnimatedGuide = false
                                            }
                                        }
                                    }
                                }

                                if (showFeaturePoints) {
                                    val currentFps = frame.fps(lastPointCloudFrame)
                                    if (currentFps < 10) {
                                        frame.acquirePointCloud()?.let { pointCloud ->
                                            if (pointCloud.timestamp != lastPointCloudTimestamp) {
                                                lastPointCloudFrame = frame
                                                lastPointCloudTimestamp = pointCloud.timestamp

                                                val pointsSize = pointCloud.ids?.limit() ?: 0

                                                if (pointCloudNodes.isNotEmpty()) {
                                                }
                                                pointCloudNodes.toList().forEach { removePointCloudNode(it) }

                                                val pointsBuffer = pointCloud.points
                                                for (index in 0 until pointsSize) {
                                                    val pointIndex = index * 4
                                                    val position =
                                                        Position(
                                                            pointsBuffer[pointIndex],
                                                            pointsBuffer[pointIndex + 1],
                                                            pointsBuffer[pointIndex + 2],
                                                        )
                                                    val confidence = pointsBuffer[pointIndex + 3]
                                                    addPointCloudNode(index, position, confidence)
                                                }

                                                pointCloud.release()
                                            }
                                        }
                                    }
                                }

                                frame.getUpdatedTrackables(Plane::class.java).forEach { plane ->
                                    if (plane.trackingState == TrackingState.TRACKING &&
                                        !detectedPlanes.contains(plane)
                                    ) {
                                        detectedPlanes.add(plane)
                                        mainScope.launch {
                                            sessionChannel.invokeMethod("onPlaneDetected", detectedPlanes.size)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        when (e) {
                            is SessionPausedException -> {
                                // Ignorer silencieusement cette exception quand la session est en pause
                                Log.d(TAG, "Session paused, skipping frame update")
                            }
                            else -> {
                                Log.e(TAG, "Error during frame update", e)
                                e.printStackTrace()
                            }
                        }
                    }
                }

                setOnGestureListener(
                    onSingleTapConfirmed = { motionEvent: MotionEvent, node: Node? ->
                        if (node != null) {
                            var anchorName: String? = null
                            var currentNode: Node? = node
                            val nodeName = node.name
                            while (currentNode != null) {
                                anchorNodesMap.forEach { (name, anchorNode) ->
                                    if (currentNode == anchorNode) {
                                        anchorName = name
                                        return@forEach
                                    }
                                }
                                if (anchorName != null) break
                                currentNode = currentNode.parent
                            }
                            if(handleTaps) {
                                objectChannel.invokeMethod("onNodeTap", listOf(nodeName))
                            }
                            true
                        } else {
                            session?.update()?.let { frame ->
                                val hitResults = frame.hitTest(motionEvent)

                                Log.d("ArView", "Hit Results count: ${hitResults.size}")

                                val planeHits =
                                    hitResults
                                        .filter { hit ->
                                            val trackable = hit.trackable
                                            trackable is Plane && trackable.trackingState == TrackingState.TRACKING
                                        }.map { hit ->
                                            mapOf(
                                                "type" to 1,
                                                "distance" to hit.distance.toDouble(),
                                                "position" to
                                                    mapOf(
                                                        "x" to hit.hitPose.tx().toDouble(),
                                                        "y" to hit.hitPose.ty().toDouble(),
                                                        "z" to hit.hitPose.tz().toDouble(),
                                                    ),
                                            )
                                        }
                                notifyPlaneOrPointTap(planeHits)
                            }
                            true
                        }
                    },
                )

                if (argShowAnimatedGuide == true && showAnimatedGuide == true) {
                    val handMotionLayout =
                        LayoutInflater
                            .from(context)
                            .inflate(R.layout.sceneform_hand_layout, rootLayout, false)
                            .apply {
                                tag = "hand_motion_layout"
                            }
                    rootLayout.addView(handMotionLayout)
                }

                if (customPlaneTexturePath != null) {
                    try {
                        val loader = FlutterInjector.instance().flutterLoader()
                        val assetKey = loader.getLookupKeyForAsset(customPlaneTexturePath)
                        val customPlaneTexture =
                            ImageTexture
                                .Builder()
                                .bitmap(materialLoader.assets, assetKey)
                                .build(engine)
                        planeRenderer.planeMaterial.defaultInstance.apply {
                            setTexture(PlaneRenderer.MATERIAL_TEXTURE, customPlaneTexture)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Erreur lors de l'application de la texture personnalisée: ${e.message}")
                        Log.e(TAG, "Stack trace:", e)
                    }
                } else {
                    Log.i(TAG, "ℹ️ Utilisation de la texture par défaut")
                }
            }
            result.success(null)
        } catch (e: Exception) {
            result.error("AR_VIEW_ERROR", e.message, null)
        }
    }

    private fun handleAddNode(
        nodeData: Map<String, Any>,
        result: MethodChannel.Result,
    ) {
        try {
            mainScope.launch {
                val node = buildModelNode(nodeData)
                if (node != null) {
                    sceneView.addChildNode(node)
                    node.name?.let { nodeName ->
                        nodesMap[nodeName] = node
                    }
                    result.success(true)
                } else {
                    result.success(false)
                }
            }
        } catch (e: Exception) {
            result.success(false)
        }
    }

    private fun handleRemoveNode(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        try {
            val nodeData = call.arguments as? Map<String, Any>
            val nodeName = nodeData?.get("name") as? String
            
            if (nodeName == null) {
                result.error("INVALID_ARGUMENT", "Node name is required", null)
                return
            }
            
            Log.d(TAG, "Attempting to remove node with name: $nodeName")
            Log.d(TAG, "Current nodes in map: ${nodesMap.keys}")
            
            nodesMap[nodeName]?.let { node ->
                // Détacher d'abord le nœud de son parent s'il en a un
                node.parent?.removeChildNode(node)
                // Puis le retirer de la scène principale
                sceneView.removeChildNode(node)
                // Nettoyer les ressources du nœud
                node.destroy()
                // Enfin le retirer de notre Map
                nodesMap.remove(nodeName)
                
                Log.d(TAG, "Node removed successfully and destroyed")
                result.success(nodeName)
            } ?: run {
                Log.e(TAG, "Node not found in nodesMap")
                result.error("NODE_NOT_FOUND", "Node with name $nodeName not found", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing node", e)
            result.error("REMOVE_NODE_ERROR", e.message, null)
        }
    }

    private fun handleTransformNode(
    call: MethodCall,
    result: MethodChannel.Result,
) {
    try {
        if (handlePans || handleRotation) {
            val name = call.argument<String>("name")
            val newTransformation: ArrayList<Double>? = call.argument<ArrayList<Double>>("transformation")

            if (name == null) {
                result.error("INVALID_ARGUMENT", "Node name is required", null)
                return
            }
            nodesMap[name]?.let { node ->
                newTransformation?.let { transform ->
                    if (transform.size != 16) {
                        result.error("INVALID_TRANSFORMATION", "Transformation must be a 4x4 matrix (16 values)", null)
                        return
                    }

                    node.apply {
                        transform(
                            position = ScenePosition(
                                x = transform[12].toFloat(),
                                y = transform[13].toFloat(),
                                z = transform[14].toFloat()
                            ),
                            rotation = SceneRotation(
                                x = kotlin.math.atan2(transform[6].toFloat(), transform[10].toFloat()),
                                y = kotlin.math.atan2(-transform[2].toFloat(), 
                                    kotlin.math.sqrt(transform[6].toFloat() * transform[6].toFloat() + 
                                    transform[10].toFloat() * transform[10].toFloat())),
                                z = kotlin.math.atan2(transform[1].toFloat(), transform[0].toFloat())
                            ),
                            scale = SceneScale(
                                x = kotlin.math.sqrt((transform[0] * transform[0] + transform[1] * transform[1] + transform[2] * transform[2]).toFloat()),
                                y = kotlin.math.sqrt((transform[4] * transform[4] + transform[5] * transform[5] + transform[6] * transform[6]).toFloat()),
                                z = kotlin.math.sqrt((transform[8] * transform[8] + transform[9] * transform[9] + transform[10] * transform[10]).toFloat())
                            )
                        )
                    }
                    result.success(null)
                } ?: result.error("INVALID_TRANSFORMATION", "Transformation is required", null)
            } ?: result.error("NODE_NOT_FOUND", "Node with name $name not found", null)
        }
    } catch (e: Exception) {
        result.error("TRANSFORM_NODE_ERROR", e.message, null)
    }
}

    private fun handleHostCloudAnchor(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        try {
            val anchorId = call.argument<String>("anchorId")
            if (anchorId == null) {
                result.error("INVALID_ARGUMENT", "Anchor ID is required", null)
                return
            }

            val session = sceneView.session
            if (session == null) {
                result.error("SESSION_ERROR", "AR Session is not available", null)
                return
            }

            if (!session.canHostCloudAnchor(sceneView.cameraNode)) {
                result.error("HOSTING_ERROR", "Insufficient visual data to host", null)
                return
            }

            val anchor = session.allAnchors.find { it.cloudAnchorId == anchorId }
            if (anchor == null) {
                result.error("ANCHOR_NOT_FOUND", "Anchor with ID $anchorId not found", null)
                return
            }

            val cloudAnchorNode = CloudAnchorNode(sceneView.engine, anchor)
            cloudAnchorNode.host(session) { cloudAnchorId, state ->
                if (state == CloudAnchorState.SUCCESS && cloudAnchorId != null) {
                    result.success(cloudAnchorId)
                } else {
                    result.error("HOSTING_ERROR", "Failed to host cloud anchor: $state", null)
                }
            }
            sceneView.addChildNode(cloudAnchorNode)
        } catch (e: Exception) {
            result.error("HOST_CLOUD_ANCHOR_ERROR", e.message, null)
        }
    }

    private fun handleResolveCloudAnchor(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        try {
            val cloudAnchorId = call.argument<String>("cloudAnchorId")
            if (cloudAnchorId == null) {
                result.error("INVALID_ARGUMENT", "Cloud Anchor ID is required", null)
                return
            }

            val session = sceneView.session
            if (session == null) {
                result.error("SESSION_ERROR", "AR Session is not available", null)
                return
            }

            CloudAnchorNode.resolve(
                sceneView.engine,
                session,
                cloudAnchorId,
            ) { state, node ->
                if (!state.isError && node != null) {
                    sceneView.addChildNode(node)
                    result.success(null)
                } else {
                    result.error("RESOLVE_ERROR", "Failed to resolve cloud anchor: $state", null)
                }
            }
        } catch (e: Exception) {
            result.error("RESOLVE_CLOUD_ANCHOR_ERROR", e.message, null)
        }
    }

    private fun handleRemoveAnchor(
        anchorName: String?,
        result: MethodChannel.Result,
    ) {
        try {
            if (anchorName == null) {
                result.error("INVALID_ARGUMENT", "Anchor name is required", null)
                return
            }

            val anchor = anchorNodesMap[anchorName]
            if (anchor != null) {
                sceneView.removeChildNode(anchor)
                anchor.anchor?.detach()
                result.success(null)
            } else {
                result.error("ANCHOR_NOT_FOUND", "Anchor with name $anchorName not found", null)
            }
        } catch (e: Exception) {
            result.error("REMOVE_ANCHOR_ERROR", e.message, null)
        }
    }

    private fun handleGetCameraPose(result: MethodChannel.Result) {
        try {
            val frame = sceneView.session?.update()
            val cameraPose = frame?.camera?.pose
            if (cameraPose != null) {
                val poseData =
                    mapOf(
                        "position" to
                            mapOf(
                                "x" to cameraPose.tx(),
                                "y" to cameraPose.ty(),
                                "z" to cameraPose.tz(),
                            ),
                        "rotation" to
                            mapOf(
                                "x" to cameraPose.rotationQuaternion[0],
                                "y" to cameraPose.rotationQuaternion[1],
                                "z" to cameraPose.rotationQuaternion[2],
                                "w" to cameraPose.rotationQuaternion[3],
                            ),
                    )
                result.success(poseData)
            } else {
                result.error("NO_CAMERA_POSE", "Camera pose is not available", null)
            }
        } catch (e: Exception) {
            result.error("CAMERA_POSE_ERROR", e.message, null)
        }
    }

    private fun handleGetAnchorPose(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        try {
            val anchorId = call.argument<String>("anchorId")
            if (anchorId == null) {
                result.error("INVALID_ARGUMENT", "Anchor ID is required", null)
                return
            }

            val anchor = sceneView.session?.allAnchors?.find { it.cloudAnchorId == anchorId }
            if (anchor != null) {
                val anchorPose = anchor.pose
                val poseData =
                    mapOf(
                        "position" to
                            mapOf(
                                "x" to anchorPose.tx(),
                                "y" to anchorPose.ty(),
                                "z" to anchorPose.tz(),
                            ),
                        "rotation" to
                            mapOf(
                                "x" to anchorPose.rotationQuaternion[0],
                                "y" to anchorPose.rotationQuaternion[1],
                                "z" to anchorPose.rotationQuaternion[2],
                                "w" to anchorPose.rotationQuaternion[3],
                            ),
                    )
                result.success(poseData)
            } else {
                result.error("ANCHOR_NOT_FOUND", "Anchor with ID $anchorId not found", null)
            }
        } catch (e: Exception) {
            result.error("ANCHOR_POSE_ERROR", e.message, null)
        }
    }

    private fun handleSnapshot(result: MethodChannel.Result) {
        try {
            mainScope.launch {
                val bitmap =
                    withContext(Dispatchers.Main) {
                        val bitmap =
                            Bitmap.createBitmap(
                                sceneView.width,
                                sceneView.height,
                                Bitmap.Config.ARGB_8888,
                            )

                        try {
                            val listener =
                                PixelCopy.OnPixelCopyFinishedListener { copyResult ->
                                    if (copyResult == PixelCopy.SUCCESS) {
                                        val byteStream = java.io.ByteArrayOutputStream()
                                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream)
                                        val byteArray = byteStream.toByteArray()
                                        result.success(byteArray)
                                    } else {
                                        result.error("SNAPSHOT_ERROR", "Failed to capture snapshot", null)
                                    }
                                }

                            PixelCopy.request(
                                sceneView,
                                bitmap,
                                listener,
                                Handler(Looper.getMainLooper()),
                            )
                        } catch (e: Exception) {
                            result.error("SNAPSHOT_ERROR", e.message, null)
                        }
                    }
            }
        } catch (e: Exception) {
            result.error("SNAPSHOT_ERROR", e.message, null)
        }
    }

    private fun handleShowPlanes(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        try {
            val showPlanes = call.argument<Boolean>("showPlanes") ?: false
            sceneView.apply {
                planeRenderer.isEnabled = showPlanes
            }
            result.success(null)
        } catch (e: Exception) {
            result.error("SHOW_PLANES_ERROR", e.message, null)
        }
    }

    private fun handleAddAnchor(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        try {
            val anchorType = call.argument<Int>("type")
            if (anchorType == 0) { // Plane Anchor
                val transform = call.argument<ArrayList<Double>>("transformation")
                val name = call.argument<String>("name")

                if (name != null && transform != null) {
                    try {
                        // Décomposer la matrice de transformation
                        val (position, rotation) = deserializeMatrix4(transform)

                        val pose =
                            Pose(
                                floatArrayOf(position.x, position.y, position.z),
                                floatArrayOf(rotation.x, rotation.y, rotation.z, 1f),
                            )

                        val anchor = sceneView.session?.createAnchor(pose)
                        if (anchor != null) {
                            val anchorNode = AnchorNode(sceneView.engine, anchor)
                            try {
                                anchorNode.transform =
                                    Transform(
                                        position = position,
                                        rotation = rotation,
                                    )
                            } catch (e: Exception) {
                                Log.w(TAG, "Transform warning suppressed: ${e.message}")
                            }

                            sceneView.addChildNode(anchorNode)
                            anchorNodesMap[name] = anchorNode
                            result.success(true)
                        } else {
                            result.success(false)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in transform calculation: ${e.message}")
                        result.success(false)
                    }
                } else {
                    result.success(false)
                }
            } else {
                result.success(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleAddAnchor: ${e.message}")
            e.printStackTrace()
            result.success(false)
        }
    }

    private fun handleInitGoogleCloudAnchorMode(result: MethodChannel.Result) {
        try {
            Log.d(TAG, "🔄 Initialisation du mode Cloud Anchor...")
            sceneView.session?.let { session ->
                session.configure(session.config.apply {
                    cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                })
            }
            result.success(null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur lors de l'initialisation du mode Cloud Anchor", e)
            mainScope.launch {
                sessionChannel.invokeMethod("onError", listOf("Error initializing cloud anchor mode: ${e.message}"))
            }
            result.error("CLOUD_ANCHOR_INIT_ERROR", e.message, null)
        }
    }

    private fun handleUploadAnchor(call: MethodCall, result: MethodChannel.Result) {
        try {
            val anchorName = call.argument<String>("name")
            Log.d(TAG, "⚓ Début de l'upload de l'ancre: $anchorName")
            
            // Vérifier si le mode Cloud Anchor est initialisé
            val session = sceneView.session
            if (session == null) {
                Log.e(TAG, "❌ Erreur: session AR non disponible")
                result.error("SESSION_ERROR", "AR Session is not available", null)
                return
            }

            // Vérifier et initialiser le mode Cloud Anchor si nécessaire
            Log.d(TAG, "🔄 Vérification de la configuration Cloud Anchor...")
            try {
                sceneView.configureSession { session, config ->
                    config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                }
                Log.d(TAG, "✅ Mode Cloud Anchor configuré avec succès")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Erreur lors de la configuration du mode Cloud Anchor", e)
                result.error("CLOUD_ANCHOR_CONFIG_ERROR", e.message, null)
                return
            }

            // Continuer avec le reste du code existant...
            if (anchorName == null) {
                Log.e(TAG, "❌ Erreur: nom de l'ancre manquant")
                result.error("INVALID_ARGUMENT", "Anchor name is required", null)
                return
            }

            Log.d(TAG, "📱 Vérification de la capacité à héberger l'ancre cloud...")
            if (!session.canHostCloudAnchor(sceneView.cameraNode)) {
                Log.e(TAG, "❌ Erreur: données visuelles insuffisantes pour héberger l'ancre cloud")
                result.error("HOSTING_ERROR", "Insufficient visual data to host", null)
                return
            }

            val anchorNode = anchorNodesMap[anchorName]
            if (anchorNode == null) {
                Log.e(TAG, "❌ Erreur: ancre non trouvée: $anchorName")
                Log.d(TAG, "📍 Ancres disponibles: ${anchorNodesMap.keys}")
                result.error("ANCHOR_NOT_FOUND", "Anchor not found: $anchorName", null)
                return
            }

            Log.d(TAG, "🔄 Création du CloudAnchorNode...")
            val cloudAnchorNode = CloudAnchorNode(sceneView.engine, anchorNode.anchor!!)
            
            Log.d(TAG, "☁️ Début de l'hébergement de l'ancre cloud...")
            cloudAnchorNode.host(session) { cloudAnchorId, state ->
                Log.d(TAG, "📡 État de l'hébergement: $state, ID: $cloudAnchorId")
                mainScope.launch {
                    if (state == CloudAnchorState.SUCCESS && cloudAnchorId != null) {
                        Log.d(TAG, "✅ Ancre cloud hébergée avec succès: $cloudAnchorId")
                        val args = mapOf(
                            "name" to anchorName,
                            "cloudanchorid" to cloudAnchorId
                        )
                        anchorChannel.invokeMethod("onCloudAnchorUploaded", args)
                        result.success(true)
                    } else {
                        Log.e(TAG, "❌ Échec de l'hébergement de l'ancre cloud: $state")
                        sessionChannel.invokeMethod("onError", listOf("Failed to host cloud anchor: $state"))
                        result.error("HOSTING_ERROR", "Failed to host cloud anchor: $state", null)
                    }
                }
            }
            
            Log.d(TAG, "➕ Ajout du CloudAnchorNode à la scène...")
            sceneView.addChildNode(cloudAnchorNode)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception lors de l'upload de l'ancre", e)
            Log.e(TAG, "Stack trace:", e)
            result.error("UPLOAD_ANCHOR_ERROR", e.message, null)
        }
    }

    private fun handleDownloadAnchor(call: MethodCall, result: MethodChannel.Result) {
        try {
            val cloudAnchorId = call.argument<String>("cloudanchorid")
            if (cloudAnchorId == null) {
                mainScope.launch {
                    sessionChannel.invokeMethod("onError", listOf("Cloud Anchor ID is required"))
                }
                result.error("INVALID_ARGUMENT", "Cloud Anchor ID is required", null)
                return
            }

            val session = sceneView.session
            if (session == null) {
                mainScope.launch {
                    sessionChannel.invokeMethod("onError", listOf("AR Session is not available"))
                }
                result.error("SESSION_ERROR", "AR Session is not available", null)
                return
            }

            CloudAnchorNode.resolve(
                sceneView.engine,
                session,
                cloudAnchorId
            ) { state, node ->
                mainScope.launch {
                    if (!state.isError && node != null) {
                        sceneView.addChildNode(node)
                        val anchorData = mapOf(
                            "type" to 0,
                            "cloudanchorid" to cloudAnchorId
                        )
                        anchorChannel.invokeMethod(
                            "onAnchorDownloadSuccess",
                            anchorData,
                            object : MethodChannel.Result {
                                override fun success(result: Any?) {
                                    val anchorName = result.toString()
                                    anchorNodesMap[anchorName] = node
                                }

                                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                                    sessionChannel.invokeMethod("onError", listOf("Error registering downloaded anchor: $errorMessage"))
                                }

                                override fun notImplemented() {
                                    sessionChannel.invokeMethod("onError", listOf("Error registering downloaded anchor: not implemented"))
                                }
                            }
                        )
                        result.success(true)
                    } else {
                        sessionChannel.invokeMethod("onError", listOf("Failed to resolve cloud anchor: $state"))
                        result.error("RESOLVE_ERROR", "Failed to resolve cloud anchor: $state", null)
                    }
                }
            }
        } catch (e: Exception) {
            mainScope.launch {
                sessionChannel.invokeMethod("onError", listOf("Error downloading anchor: ${e.message}"))
            }
            result.error("DOWNLOAD_ANCHOR_ERROR", e.message, null)
        }
    }

    override fun getView(): View = rootLayout

    override fun dispose() {
        Log.i(TAG, "dispose")
        sessionChannel.setMethodCallHandler(null)
        objectChannel.setMethodCallHandler(null)
        anchorChannel.setMethodCallHandler(null)
        nodesMap.clear()
        sceneView.destroy()
        pointCloudNodes.toList().forEach { removePointCloudNode(it) }
        pointCloudModelInstances.clear()
    }

    private fun notifyError(error: String) {
        mainScope.launch {
            sessionChannel.invokeMethod("onError", listOf(error))
        }
    }

    private fun notifyCloudAnchorUploaded(args: Map<String, Any>) {
        mainScope.launch {
            anchorChannel.invokeMethod("onCloudAnchorUploaded", args)
        }
    }

    private fun notifyAnchorDownloadSuccess(
        anchorData: Map<String, Any>,
        result: MethodChannel.Result,
    ) {
        mainScope.launch {
            anchorChannel.invokeMethod(
                "onAnchorDownloadSuccess",
                anchorData,
                object : MethodChannel.Result {
                    override fun success(result: Any?) {
                        val anchorName = result.toString()
                        // Mettre à jour l'ancre avec le nom reçu
                    }

                    override fun error(
                        errorCode: String,
                        errorMessage: String?,
                        errorDetails: Any?,
                    ) {
                        notifyError("Error while registering downloaded anchor: $errorMessage")
                    }

                    override fun notImplemented() {
                        notifyError("Error while registering downloaded anchor")
                    }
                },
            )
        }
    }

    private fun notifyPlaneOrPointTap(hitResults: List<Map<String, Any>>) {
        mainScope.launch {
            try {
                val serializedResults = ArrayList<HashMap<String, Any>>()
                hitResults.forEach { hit ->
                    serializedResults.add(serializeHitResult(hit))
                }
                sessionChannel.invokeMethod("onPlaneOrPointTap", serializedResults)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getPointCloudModelInstance(): ModelInstance? {
        if (pointCloudModelInstances.isEmpty()) {
            pointCloudModelInstances =
                sceneView.modelLoader
                    .createInstancedModel(
                        assetFileLocation = "models/point_cloud.glb",
                        count = 1000,
                    ).toMutableList()
        }
        return pointCloudModelInstances.removeLastOrNull()
    }

    private fun addPointCloudNode(
        id: Int,
        position: Position,
        confidence: Float,
    ) {
        if (pointCloudNodes.size < 1000) { // Limite max de points
            getPointCloudModelInstance()?.let { modelInstance ->
                val pointCloudNode =
                    PointCloudNode(
                        modelInstance = modelInstance,
                        id = id,
                        confidence = confidence,
                    ).apply {
                        this.position = position
                    }
                pointCloudNodes += pointCloudNode
                sceneView.addChildNode(pointCloudNode)
            }
        }
    }

    private fun removePointCloudNode(pointCloudNode: PointCloudNode) {
        pointCloudNodes -= pointCloudNode
        sceneView.removeChildNode(pointCloudNode)
        pointCloudNode.destroy()
    }

    private fun makeWorldOriginNode(context: Context): Node {
        val axisSize = 0.1f
        val axisRadius = 0.005f
        
        // Utilisation de l'engine de sceneView
        val engine = sceneView.engine
        val materialLoader = MaterialLoader(engine, context)
        
        // Création du noeud racine
        val rootNode = Node(engine = engine)
        
        // Création des cylindres avec leurs matériaux respectifs
        val xNode = CylinderNode(
            engine = engine,
            radius = axisRadius,
            height = axisSize,
            materialInstance = materialLoader.createColorInstance(
                color = colorOf(1f, 0f, 0f, 1f),
                metallic = 0.0f,
                roughness = 0.4f
            )
        )
        
        val yNode = CylinderNode(
            engine = engine,
            radius = axisRadius,
            height = axisSize,
            materialInstance = materialLoader.createColorInstance(
                color = colorOf(0f, 1f, 0f, 1f),
                metallic = 0.0f,
                roughness = 0.4f
            )
        )
        
        val zNode = CylinderNode(
            engine = engine,
            radius = axisRadius,
            height = axisSize,
            materialInstance = materialLoader.createColorInstance(
                color = colorOf(0f, 0f, 1f, 1f),
                metallic = 0.0f,
                roughness = 0.4f
            )
        )

        rootNode.addChildNode(xNode)
        rootNode.addChildNode(yNode)
        rootNode.addChildNode(zNode)

        // Positionnement des axes
        xNode.position = Position(axisSize / 2, 0f, 0f)
        xNode.rotation = Rotation(0f, 0f, 90f)  // Rotation autour de l'axe Z

        yNode.position = Position(0f, axisSize / 2, 0f)
        // Pas besoin de rotation pour l'axe Y car il est déjà orienté correctement

        zNode.position = Position(0f, 0f, axisSize / 2)
        zNode.rotation = Rotation(90f, 0f, 0f)  // Rotation autour de l'axe X

        return rootNode
    }

    private fun handleShowWorldOrigin(show: Boolean) {
        if (show) {
            // Création du nouveau node seulement si nécessaire
            if (worldOriginNode == null) {
                worldOriginNode = makeWorldOriginNode(viewContext)
            }
            // Utilisation du safe call operator
            worldOriginNode?.let { node ->
                sceneView.addChildNode(node)
            }
        } else {
            // Utilisation du safe call operator
            worldOriginNode?.let { node ->
                sceneView.removeChildNode(node)
            }
            // Optionnel : remettre à null après suppression
            worldOriginNode = null
        }
    }

    
}
