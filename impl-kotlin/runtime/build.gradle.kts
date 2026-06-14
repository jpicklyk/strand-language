plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":core"))
    api(project(":verifier"))
    api(project(":interpreter"))
    // Q-054: the StrandRuntime facade runs the full verify -> schema-check ->
    // evaluate pipeline, so it reaches the SchemaChecker. :schema sits on the
    // same :interpreter -> :verifier -> :core spine as :runtime, so the edge is
    // acyclic (the CLI already depends on both).
    api(project(":schema"))
    // Q-058: run-by-hash reads a ProgramImage from the on-disk PersistentStore
    // and admits it through the federation path, both of which live in :hashing.
    // :hashing depends only on :core, so the :runtime -> :hashing edge is acyclic
    // (the CLI and the :corpus test sourceset already depend on :hashing).
    api(project(":hashing"))
    // kotlinx-serialization-json is already brought in transitively via :core,
    // but the runtime's EventCodec uses the parser directly — declare it as a
    // compile-time dependency rather than relying on transitive resolution.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    // Q-059: ValueCodec serializes the Value.MapV / Value.SetV variants, which
    // carry kotlinx.collections.immutable PersistentMap / PersistentSet. The
    // library reaches :runtime only transitively via :interpreter's
    // `implementation` dependency, so it is not on the compile classpath —
    // declare it directly here.
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.7")
    // Layer 6 step 2: per-machine coroutine actors over Channel<Value>, plus
    // select-based multi-stream merge per Q-009's nondeterministic-merge default.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    // Virtual-time test dispatcher for actor-loop assertions without wall-clock dependence.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

// The demonstration programs (compiled canonical dag-json) live at the
// top-level demos/<demo>/programs/ as a single source of truth (each demo is
// self-contained and discoverable there). Copy them onto the test classpath
// under /demo/programs/ so the driver/test pairs load them via
// getResourceAsStream without duplicating the JSON in git or depending on a
// fragile relative working-directory path.
//   - demos/containment-host/programs : the Q-044 containment demonstration
//   - demos/replay-timetravel/programs : the Q-059/Q-065 replay demonstration
//   - demos/plugin-host/programs : the Q-039/Q-031/N-036/Q-064 fine-grained
//     capability-attenuation + confused-deputy demonstration
private val containmentProgramsDir =
    projectDir.parentFile.parentFile.resolve("demos/containment-host/programs")
private val replayProgramsDir =
    projectDir.parentFile.parentFile.resolve("demos/replay-timetravel/programs")
private val pluginHostProgramsDir =
    projectDir.parentFile.parentFile.resolve("demos/plugin-host/programs")
//   - demos/output-by-construction/programs : the Q-053/N-048 + Q-035/Q-047
//     correct-by-construction structured-output demonstration
private val outputByConstructionProgramsDir =
    projectDir.parentFile.parentFile.resolve("demos/output-by-construction/programs")
//   - demos/agent-workflow/programs : the bounded-agent-workflow demonstration
//     (per-provider LLM + ToolDef + Attempt retry under a surfaced effect bound)
private val agentWorkflowProgramsDir =
    projectDir.parentFile.parentFile.resolve("demos/agent-workflow/programs")
//   - demos/clean-room/programs : the proof-of-no-exfiltration demonstration
//     (the effect closure with no egress category is a structural non-exfiltration proof)
private val cleanRoomProgramsDir =
    projectDir.parentFile.parentFile.resolve("demos/clean-room/programs")
//   - demos/mcp-tool-manifest/programs : the verifiable-tool-capability-manifest
//     demonstration (an N-046 ModuleManifest whose declared effects are machine-checked)
private val mcpToolManifestProgramsDir =
    projectDir.parentFile.parentFile.resolve("demos/mcp-tool-manifest/programs")
//   - demos/llm-virtualization/programs : the Handler-based LLM-virtualization
//     demonstration (a Handler intercepting LLM.Generate subtracts it from the closure)
private val llmVirtualizationProgramsDir =
    projectDir.parentFile.parentFile.resolve("demos/llm-virtualization/programs")
//   - demos/bounded-rag/programs : the bounded-retrieval (RAG) demonstration
//     (Embed + Vector.Read refined to one store + Generate, surfaced and refinement-gated)
private val boundedRagProgramsDir =
    projectDir.parentFile.parentFile.resolve("demos/bounded-rag/programs")
//   - demos/skill-workflow/programs : the model-as-policy / graph-as-actuator
//     demonstration (untrusted model decisions as data into a verified bounded actuator)
private val skillWorkflowProgramsDir =
    projectDir.parentFile.parentFile.resolve("demos/skill-workflow/programs")
//   - demos/multi-agent-supervisor/programs : the supervised multi-agent group
//     demonstration (concurrent per-agent-bounded actors; a rogue agent is contained)
private val multiAgentSupervisorProgramsDir =
    projectDir.parentFile.parentFile.resolve("demos/multi-agent-supervisor/programs")
//   - demos/verified-llm-output/programs : the constrain-then-verify structured-output
//     demonstration (N-045 response-schema constraint + Q-047 runtime invariant check)
private val verifiedLlmOutputProgramsDir =
    projectDir.parentFile.parentFile.resolve("demos/verified-llm-output/programs")

tasks.named<ProcessResources>("processTestResources") {
    from(containmentProgramsDir) {
        include("*.json")
        into("demo/programs")
    }
    from(replayProgramsDir) {
        include("*.json")
        into("demo/programs")
    }
    from(pluginHostProgramsDir) {
        include("*.json")
        into("demo/programs")
    }
    from(outputByConstructionProgramsDir) {
        include("*.json")
        into("demo/programs")
    }
    from(agentWorkflowProgramsDir) {
        include("*.json")
        into("demo/programs")
    }
    from(cleanRoomProgramsDir) {
        include("*.json")
        into("demo/programs")
    }
    from(mcpToolManifestProgramsDir) {
        include("*.json")
        into("demo/programs")
    }
    from(llmVirtualizationProgramsDir) {
        include("*.json")
        into("demo/programs")
    }
    from(boundedRagProgramsDir) {
        include("*.json")
        into("demo/programs")
    }
    from(skillWorkflowProgramsDir) {
        include("*.json")
        into("demo/programs")
    }
    from(multiAgentSupervisorProgramsDir) {
        include("*.json")
        into("demo/programs")
    }
    from(verifiedLlmOutputProgramsDir) {
        include("*.json")
        into("demo/programs")
    }
}

tasks.test {
    inputs.dir(containmentProgramsDir)
    inputs.dir(replayProgramsDir)
    inputs.dir(pluginHostProgramsDir)
    inputs.dir(outputByConstructionProgramsDir)
    inputs.dir(agentWorkflowProgramsDir)
    inputs.dir(cleanRoomProgramsDir)
    inputs.dir(mcpToolManifestProgramsDir)
    inputs.dir(llmVirtualizationProgramsDir)
    inputs.dir(boundedRagProgramsDir)
    inputs.dir(skillWorkflowProgramsDir)
    inputs.dir(multiAgentSupervisorProgramsDir)
    inputs.dir(verifiedLlmOutputProgramsDir)
}

// Print the containment-demonstration transcript. The driver lives in the test
// source set (it shares scenario code with ContainmentDemoTest), so it runs on
// the test runtime classpath. Usage: `./gradlew :runtime:containmentDemo -q`.
tasks.register<JavaExec>("containmentDemo") {
    group = "verification"
    description = "Print the untrusted-agent-program host containment demonstration transcript."
    dependsOn("testClasses", "processTestResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.strand.runtime.ContainmentDemo")
}

// Print the replay / time-travel / restart-resume demonstration transcript.
// Like containmentDemo, the driver lives in the test source set (it shares
// scenario code with ReplayDemoTest). Usage: `./gradlew :runtime:replayDemo -q`.
tasks.register<JavaExec>("replayDemo") {
    group = "verification"
    description = "Print the sound deterministic replay / time-travel / restart-resume demonstration transcript."
    dependsOn("testClasses", "processTestResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.strand.runtime.ReplayDemo")
}

// Print the fine-grained capability-attenuation + confused-deputy-defense
// demonstration transcript. Like the others, the driver lives in the test
// source set (it shares scenario code with PluginHostDemoTest). Usage:
// `./gradlew :runtime:pluginHostDemo -q`.
tasks.register<JavaExec>("pluginHostDemo") {
    group = "verification"
    description = "Print the plugin-host fine-grained capability attenuation / confused-deputy demonstration transcript."
    dependsOn("testClasses", "processTestResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.strand.runtime.PluginHostDemo")
}

// Print the correct-by-construction structured-output demonstration transcript.
// Like the others, the driver lives in the test source set (it shares scenario
// code with OutputByConstructionDemoTest). Usage:
// `./gradlew :runtime:outputByConstructionDemo -q`.
tasks.register<JavaExec>("outputByConstructionDemo") {
    group = "verification"
    description = "Print the correct-by-construction structured-output demonstration transcript (N-048 + schema invariants)."
    dependsOn("testClasses", "processTestResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.strand.runtime.OutputByConstructionDemo")
}

// Print the bounded-agent-workflow demonstration transcript. Like the others,
// the driver lives in the test source set (it shares scenario code with
// AgentWorkflowDemoTest). Usage: `./gradlew :runtime:agentWorkflowDemo -q`.
tasks.register<JavaExec>("agentWorkflowDemo") {
    group = "verification"
    description = "Print the bounded-agent-workflow demonstration transcript (per-provider LLM + ToolDef + Attempt retry under a surfaced effect bound)."
    dependsOn("testClasses", "processTestResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.strand.runtime.AgentWorkflowDemo")
}

// Print the clean-room proof-of-no-exfiltration demonstration transcript. Like
// the others, the driver lives in the test source set (it shares scenario code
// with CleanRoomDemoTest). Usage: `./gradlew :runtime:cleanRoomDemo -q`.
tasks.register<JavaExec>("cleanRoomDemo") {
    group = "verification"
    description = "Print the clean-room proof-of-no-exfiltration demonstration transcript (effect closure with no egress category = structural non-exfiltration proof)."
    dependsOn("testClasses", "processTestResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.strand.runtime.CleanRoomDemo")
}

// Print the MCP-tool verifiable-capability-manifest demonstration transcript.
// Like the others, the driver lives in the test source set (it shares scenario
// code with McpToolManifestDemoTest). Usage: `./gradlew :runtime:mcpToolManifestDemo -q`.
tasks.register<JavaExec>("mcpToolManifestDemo") {
    group = "verification"
    description = "Print the MCP-tool verifiable-capability-manifest demonstration transcript (an N-046 ModuleManifest whose declared effects are machine-checked against the code)."
    dependsOn("testClasses", "processTestResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.strand.runtime.McpToolManifestDemo")
}

// Print the Handler-based LLM-virtualization demonstration transcript. Like the
// others, the driver lives in the test source set (it shares scenario code with
// LlmVirtualizationDemoTest). Usage: `./gradlew :runtime:llmVirtualizationDemo -q`.
tasks.register<JavaExec>("llmVirtualizationDemo") {
    group = "verification"
    description = "Print the Handler-based LLM-virtualization demonstration transcript (a Handler intercepting LLM.Generate subtracts it from the effect closure)."
    dependsOn("testClasses", "processTestResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.strand.runtime.LlmVirtualizationDemo")
}

// Print the bounded-retrieval (RAG) demonstration transcript. Like the others,
// the driver lives in the test source set (it shares scenario code with
// BoundedRagDemoTest). Usage: `./gradlew :runtime:boundedRagDemo -q`.
tasks.register<JavaExec>("boundedRagDemo") {
    group = "verification"
    description = "Print the bounded-retrieval (RAG) demonstration transcript (Embed + Vector.Read refined to one store + Generate, with the data-access capability surfaced and refinement-gated)."
    dependsOn("testClasses", "processTestResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.strand.runtime.BoundedRagDemo")
}

// Print the model-as-policy / graph-as-actuator (skill-workflow) demonstration
// transcript. Like the others, the driver lives in the test source set (it shares
// scenario code with SkillWorkflowDemoTest). Usage: `./gradlew :runtime:skillWorkflowDemo -q`.
tasks.register<JavaExec>("skillWorkflowDemo") {
    group = "verification"
    description = "Print the skill-workflow demonstration transcript (untrusted model decisions as data flowing into a verified, capability-bounded actuator graph; a poisoned decision cannot expand the action set)."
    dependsOn("testClasses", "processTestResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.strand.runtime.SkillWorkflowDemo")
}

// Print the supervised multi-agent group demonstration transcript. Like the
// others, the driver lives in the test source set (it shares scenario code with
// MultiAgentSupervisorDemoTest). Usage: `./gradlew :runtime:multiAgentSupervisorDemo -q`.
tasks.register<JavaExec>("multiAgentSupervisorDemo") {
    group = "verification"
    description = "Print the supervised multi-agent group demonstration transcript (concurrent per-agent-bounded actors as a verified orchestration; a rogue agent's over-reach is contained and the group survives)."
    dependsOn("testClasses", "processTestResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.strand.runtime.MultiAgentSupervisorDemo")
}

// Print the constrain-then-verify structured-output demonstration transcript.
// Like the others, the driver lives in the test source set (it shares scenario
// code with VerifiedLlmOutputDemoTest). Usage: `./gradlew :runtime:verifiedLlmOutputDemo -q`.
tasks.register<JavaExec>("verifiedLlmOutputDemo") {
    group = "verification"
    description = "Print the constrain-then-verify structured-output demonstration transcript (N-045 ResponseSchemaSpec constrains the model; Q-047 runtime invariant verifies what it returns)."
    dependsOn("testClasses", "processTestResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.strand.runtime.VerifiedLlmOutputDemo")
}
