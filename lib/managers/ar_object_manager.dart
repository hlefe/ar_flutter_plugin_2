import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/models/ar_anchor.dart';
import 'package:ar_flutter_plugin_2/models/ar_node.dart';
import 'package:ar_flutter_plugin_2/utils/json_converters.dart';
import 'package:flutter/services.dart';
import 'package:vector_math/vector_math_64.dart';
import 'package:vector_math/vector_math.dart' as math;

// Type definitions to enforce a consistent use of the API
typedef NodeTapResultHandler = void Function(List<String> nodes);
typedef NodePanStartHandler = void Function(String node);
typedef NodePanChangeHandler = void Function(String node);
typedef NodePanEndHandler = void Function(String node, Matrix4 transform);
typedef NodeRotationStartHandler = void Function(String node);
typedef NodeRotationChangeHandler = void Function(String node);
typedef NodeRotationEndHandler = void Function(String node, Matrix4 transform);
typedef PhysicsNodeCollisionHandler = void Function(String nodeName, String? collidedWithNodeName);

/// Manages the all node-related actions of an [ARView]
class ARObjectManager {
  /// Platform channel used for communication from and to [ARObjectManager]
  late MethodChannel _channel;

  /// Debugging status flag. If true, all platform calls are printed. Defaults to false.
  final bool debug;

  /// Callback function that is invoked when the platform detects a tap on a node
  NodeTapResultHandler? onNodeTap;
  NodePanStartHandler? onPanStart;
  NodePanChangeHandler? onPanChange;
  NodePanEndHandler? onPanEnd;
  NodeRotationStartHandler? onRotationStart;
  NodeRotationChangeHandler? onRotationChange;
  NodeRotationEndHandler? onRotationEnd;
  PhysicsNodeCollisionHandler? onPhysicsNodeCollision;

  ARObjectManager(int id, {this.debug = false}) {
    _channel = MethodChannel('arobjects_$id');
    _channel.setMethodCallHandler(_platformCallHandler);
    if (debug) {
      print("ARObjectManager initialized");
    }
  }

  Future<void> _platformCallHandler(MethodCall call) {
    if (debug) {
      print('_platformCallHandler call ${call.method} ${call.arguments}');
    }
    try {
      switch (call.method) {
        case 'onError':
          print(call.arguments);
          break;
        case 'onNodeTap':
          if (onNodeTap != null) {
            final tappedNodes = call.arguments as List<dynamic>;
            onNodeTap!(tappedNodes
                .map((tappedNode) => tappedNode.toString())
                .toList());
          }
          break;
        case 'onPanStart':
          if (onPanStart != null) {
            final tappedNode = call.arguments as String;
            // Notify callback
            onPanStart!(tappedNode);
          }
          break;
        case 'onPanChange':
          if (onPanChange != null) {
            final tappedNode = call.arguments as String;
            // Notify callback
            onPanChange!(tappedNode);
          }
          break;
        case 'onPanEnd':
          if (onPanEnd != null) {
            final tappedNodeName = call.arguments["name"] as String;
            final transform =
                MatrixConverter().fromJson(call.arguments['transform'] as List);

            // Notify callback
            onPanEnd!(tappedNodeName, transform);
          }
          break;
        case 'onRotationStart':
          if (onRotationStart != null) {
            final tappedNode = call.arguments as String;
            onRotationStart!(tappedNode);
          }
          break;
        case 'onRotationChange':
          if (onRotationChange != null) {
            final tappedNode = call.arguments as String;
            onRotationChange!(tappedNode);
          }
          break;
        case 'onRotationEnd':
          if (onRotationEnd != null) {
            final tappedNodeName = call.arguments["name"] as String;
            final transform =
                MatrixConverter().fromJson(call.arguments['transform'] as List);

            // Notify callback
            onRotationEnd!(tappedNodeName, transform);
          }
          break;
        case 'onPhysicsNodeCollision':
          if (onPhysicsNodeCollision != null) {
            final collisionData = call.arguments as Map<dynamic, dynamic>;
            final String nodeName = collisionData['nodeName'];
            final String? collidedWithNodeName = collisionData['collidedWithNodeName'];
            onPhysicsNodeCollision!(nodeName, collidedWithNodeName);
          }
          break;
        default:
          if (debug) {
            print('Unimplemented method ${call.method} ');
          }
      }
    } catch (e) {
      print('Error caught: ' + e.toString());
    }
    return Future.value();
  }

  /// Sets up the AR Object Manager
  onInitialize() {
    _channel.invokeMethod<void>('init', {});
  }

  /// Add given node to the given anchor of the underlying AR scene (or to its top-level if no anchor is given) and listen to any changes made to its transformation
  Future<bool?> addNode(ARNode node, {ARPlaneAnchor? planeAnchor}) async {
    try {
      node.transformNotifier.addListener(() {
        _channel.invokeMethod<void>('transformationChanged', {
          'name': node.name,
          'transformation':
              MatrixValueNotifierConverter().toJson(node.transformNotifier)
        });
      });
      if (planeAnchor != null) {
        planeAnchor.childNodes.add(node.name);
        return await _channel.invokeMethod<bool>('addNodeToPlaneAnchor',
            {'node': node.toMap(), 'anchor': planeAnchor.toJson()});
      } else {
        return await _channel.invokeMethod<bool>('addNode', node.toMap());
      }
    } on PlatformException catch (e) {
      return false;
    }
  }

  /// Remove given node from the AR Scene
  removeNode(ARNode node) {
    _channel.invokeMethod<String>('removeNode', {'name': node.name});
  }

  /// Starts the physics simulation for the specified node with an initial velocity.
  /// The node must have been previously added using [addNode].
  ///
  /// [nodeName]: The unique name of the node to start physics for.
  /// [initialVelocity]: The initial velocity vector (in m/s) in world coordinates.
  Future<void> startPhysics(String nodeName, Vector3 initialVelocity) async {
    if (nodeName.isEmpty) {
      print("❌ Error: nodeName cannot be empty for startPhysics.");
      return;
    }
    try {
      await _channel.invokeMethod('startPhysics', {
        'nodeName': nodeName,
        'initialVelocity': [initialVelocity.x, initialVelocity.y, initialVelocity.z],
      });
      if (debug) {
        print("🚀 Physics started for '$nodeName' with velocity $initialVelocity");
      }
    } on PlatformException catch (e) {
      print("❌ Failed to start physics for node '$nodeName': ${e.message}");
    }
  }

  Future<void> transformNode({
    required String name,
    required Vector3 translation,
    required Vector3 rotation,
    required Vector3 scale,
  }) async {
    print("=== TRANSFORM NODE ===");
    print("Nom du nœud: $name");
    print("Translation: x=${translation.x}, y=${translation.y}, z=${translation.z}");
    print("Rotation (radians): x=${rotation.x}, y=${rotation.y}, z=${rotation.z}");
    print("Scale: x=${scale.x}, y=${scale.y}, z=${scale.z}");

    final node = _nodes[name];
    if (node == null) {
      print("ERREUR: Nœud non trouvé avec le nom: $name");
      throw Exception('Node not found');
    }

    node.transform = Matrix4.compose(
      translation,
      Quaternion.fromEuler(rotation.x, rotation.y, rotation.z),
      scale,
    );

    print("Nouvelle transformation appliquée au nœud: ${node.transform.storage}");
    await _channel.invokeMethod('transformNode', {
      'name': name,
      'translation': translation.storage,
      'rotation': rotation.storage,
      'scale': scale.storage,
    });
  }
}
