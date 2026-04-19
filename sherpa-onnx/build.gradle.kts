// Wrapper module for the local sherpa-onnx AAR.
// Android Gradle Plugin rejects direct local .aar dependencies of a library
// module that itself produces an AAR (":ime-ai"). Wrapping the AAR in its own
// project-style module is the AGP-recommended workaround.
configurations.maybeCreate("default")
artifacts.add("default", file("sherpa-onnx-1.12.34.aar"))
