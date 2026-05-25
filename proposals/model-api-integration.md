# Model-API integration for dynamic-cost evaluation

**Document:** `proposals/model-api-integration.md`
**Status:** Draft
**Date:** 2026-05-25
**Concerns:** [`00-motivation.md`](../00-motivation.md), [`research-plan.md`](../research-plan.md), [`evaluation/README.md`](../evaluation/README.md), [`proposals/implemented/llm-authoring-layer.md`](implemented/llm-authoring-layer.md), [`proposals/implemented/layer-a-density.md`](implemented/layer-a-density.md), [Q-020](../open-questions.md#Q-020), [Q-021](../open-questions.md#Q-021), [Q-034](../open-questions.md#Q-034)
**Scope:** Medium-large for the initial slice; subsequent shipping units expand baselines, task suite, and metric coverage

The Layer A density v4 work has settled the static-cost half of Strand's token-efficiency claim: the three-task evaluation suite measures Strand Layer A at 0.81× Python+type-hints geomean, below the §6 projection floor for stacks without tokenizer alignment. The dynamic-cost half — tokens-per-successful-task across an agent's verifier-feedback retry loop, and first-pass verification rate against the canonical and conventional-language baselines — remains unmeasured. The current static framework counts bytes in reference solutions; it cannot answer whether a real LLM converges on a verified Strand program in fewer total tokens than it converges on a working Python program. This proposal specifies the model-API integration that closes that measurement gap.

## 1. Problem statement

The headline claim Strand needs to validate is empirical: when an LLM emits programs against the verifier-feedback loop, the *total* tokens spent (across all retries until success) is lower for Strand than for the named Q-021 baselines on a representative task suite. Static per-emission cost is one factor — Strand Layer A density v4 has now closed it — but the *retry economics* are the other factor, and they remain unmeasured. A representation that is 0.81× of Python's per-emission cost still loses overall if it requires 5× as many retries to converge.

Two questions sit downstream of this measurement.

The first is whether the verifier's structural correctness contract actually translates to fewer retries in practice. Strand's design bet is that the verifier rejects an emission with structured error data the agent can act on, and that the resulting retries converge faster than the equivalent loop in Python (where "verification" is "run the program and see what happens"). This is empirically testable only against a real model API.

The second is the comparison with conventional baselines on a uniform basis. Q-021's five baselines (Python+type-hints, Kotlin Coroutines, Rust, TypeScript-strict, SimPy/ShortCoder) each have different notions of "verification" — Rust's compiler is closest to Strand's verifier; Python's `mypy --strict` is in the middle; vanilla Python has only runtime success. A fair comparison framework must normalize across these so the headline number — geomean of `tokens-per-successful-task` ratios — is meaningful.

The framework that answers these questions has three operational requirements: it must drive a real model API (the first integration is Anthropic's Claude API, since that is the model running Claude Code and the project's primary agent surface); it must run the agent's emit → verify → retry loop with structured feedback; and it must support multiple output-language baselines so the conventional-vs-Strand comparison is on equal footing. The framework must also be usable without API credentials — most operators will not have an Anthropic API key, and CI must not burn API budget on every test run.

Resolution of the dynamic-cost half of Q-021 and Q-034 requires this framework. The current `evaluation/measure.sh` framework is purely static and is unchanged by this proposal; the new framework lives alongside it.

## 2. Prior art

The model-evaluation tooling ecosystem has converged on a small number of patterns the framework adapts rather than re-invents.

- **lm-evaluation-harness** ([EleutherAI](https://github.com/EleutherAI/lm-evaluation-harness), [Gao et al., 2021](https://github.com/EleutherAI/lm-evaluation-harness/blob/main/docs/README.md)) — the de-facto standard for benchmarking language models against a task suite. Tasks are YAML-defined; an adapter dispatches to a model backend (OpenAI, Anthropic, local HuggingFace); metrics are computed task-by-task. Its strength is the task-format abstraction: a task knows what its prompt looks like and how its outputs are scored, independent of which model runs it. Its weakness for the present case is the single-turn assumption — every task is one prompt, one completion, one score. Multi-turn agent loops (the verifier-feedback retry loop) require an extension lm-eval-harness has only partial support for.

- **BIG-bench** ([Srivastava et al., 2022](https://arxiv.org/abs/2206.04615)) — task suite with a similar adapter model. Same single-turn limitation. Useful prior art for the per-task `description.md` + `reference.<lang>.<ext>` pattern the proposal adopts.

- **HumanEval / MBPP** ([Chen et al., 2021](https://arxiv.org/abs/2107.03374); [Austin et al., 2021](https://arxiv.org/abs/2108.07732)) — code-generation benchmarks structured as `prompt → program → execute against test cases → pass/fail`. Their `pass@k` metric (any of k samples passes) is the closest existing precedent for "first-pass verification rate" but does not model the retry loop. Their execution sandbox (subprocess invocation of the candidate program with stdin/stdout assertion) is the pattern the framework's success-check uses for Python baselines.

- **SWE-bench** ([Jimenez et al., 2024](https://arxiv.org/abs/2310.06770)) — agentic coding benchmark with multi-turn loops. Tasks are paired with repository state and expected diffs; the agent loops with shell access. The retry-loop structure is closer to what the present proposal needs, though SWE-bench's framing (repo-level edits with test-driven verification) is heavier than per-task generation.

- **Anthropic's tool-use evaluations** ([Anthropic API docs](https://docs.anthropic.com/en/docs/agents-and-tools/tool-use/overview)) — the platform-native pattern for multi-turn loops: the model emits a tool call, the harness executes the tool, the result feeds back into the next turn. The Strand verifier maps naturally onto the "tool" role: an "emit_strand_program" tool whose result is `{verified: true, root_type: ...}` or `{verified: false, errors: [...]}`. The framework can be structured either as raw text generation with verifier-error-as-user-message or as tool-use with verifier-as-tool; the proposal evaluates both interfaces.

- **OpenAI Evals** ([OpenAI](https://github.com/openai/evals)) — registry-driven evaluation harness with a similar multi-baseline pattern. The "completion function" abstraction (a callable that emits a string given a prompt) is the abstraction the proposal's `EmissionBackend` interface mirrors.

- **VCR / pytest-vcr / responses** ([VCR.py](https://github.com/kevin1024/vcrpy)) — HTTP-request recording libraries that capture live API responses to disk on first run and replay them on subsequent runs. The pattern is the foundation of the proposal's "mocked mode": a recorded-response cache that lets CI and contributors run the framework without burning API credits. The framework's recording format is structured (per-(task, configuration, model) JSON file) rather than VCR's HTTP-cassette format, because the recording is at the conversation level, not the HTTP level.

- **Outlines, llama.cpp grammar-constrained generation** ([Outlines](https://github.com/dottxt-ai/outlines), [llama.cpp grammars](https://github.com/ggerganov/llama.cpp/blob/master/grammars/README.md)) — already integrated with the Strand authoring stack via Layer B's `strand grammar` GBNF emitter. The framework reuses the GBNF when the configuration enables grammar-constrained decoding; no new integration is required.

- **DSPy** ([Khattab et al., 2024](https://arxiv.org/abs/2310.03714)) — programming model for LLM pipelines with structured retry semantics. The "few-shot-with-self-correction" pattern DSPy formalizes is what the framework's multi-turn loop implements, simplified to the task at hand (a single emit → verify → revise cycle, not a learned-policy retry strategy).

## 3. Technique evaluation

The brief raises ten design questions. The proposal evaluates each before committing to a stack, then assembles the chosen options into §4–§5.

### 3.1 Language and runtime for the harness

Three plausible choices. **Python** (the ML-eval convention; best Anthropic SDK support; aligns with the Python+type-hints baseline since the harness can import and execute Python reference solutions in-process). **Kotlin** (matches the existing `impl/` layout; the harness can call `Authoring.compileToDagJson` directly without subprocess overhead; build-system integration is trivial). **Bash** (matches the static `evaluation/measure.sh`; the simplest possible addition).

Recommendation: **Python**. The framework needs (a) async HTTP for the Anthropic SDK, (b) a structured retry loop with multi-turn message history, (c) deterministic execution of Python reference baselines, (d) statistical analysis (bootstrap CIs, geometric means) on the results. Each of these is a one-line dependency in Python (`anthropic`, native async, subprocess for non-Python baselines, `scipy.stats`); each is a multi-day project in Kotlin (Ktor + serialization + JVM-bridge for Python baselines) and impractical in Bash. The cost of Python — adding a Python toolchain dependency to the project — is paid once at framework setup, not per-task or per-run. The shell-out approach to the Strand compiler (§3.2) means the harness never imports JVM code; the integration boundary is `subprocess.run([..., "strand", "verify", ...])`. Bash remains the right tool for the static `measure.sh` framework, which the proposal explicitly does not touch.

### 3.2 Module structure and Strand-compiler integration

Two axes: where the code lives, and how it talks to the Strand compiler.

For location, **a top-level `evaluation/dynamic/` subdirectory containing a Python package `strand_eval/`** keeps the dynamic framework adjacent to the static one and outside `impl/` (since the framework spans multiple languages). The pattern mirrors `evaluation/tasks/` which is already a sibling of `evaluation/measure.sh`. No Gradle module is added; the framework does not link against `impl/`.

For Strand-compiler integration, two options: **shell out to the `strand` CLI** or **JNI / JPype into the Kotlin classes directly**. Shell-out is slower per call (JVM startup + Gradle wrapper cost is ~1-2 seconds per `strand verify`) but trivially correct: the harness consumes the same CLI surface a real agent would use, and the CLI's stdout/stderr contract is stable (it is the agent-facing interface in practice). JNI is faster per call but requires the framework to manage a long-lived JVM, handle classpath setup, and re-implement error decoding from Kotlin objects to Python data. The shell-out cost is amortized: per-task latency is dominated by the model API call (1-10 seconds), not the verifier (1-2 seconds with cold JVM). For a representative ~10-task suite × ~5 retries × ~6 configurations, the JVM-startup cost is order ~5 minutes per full run, against an API cost of ~30 minutes. The shell-out wins on engineering simplicity by enough margin to ship first.

Recommendation: **shell out to `strand author --emit-json` followed by `strand verify`**, with the JNI alternative flagged as a Could-also in §8. The framework can later switch to JNI without changing the conceptual structure if measurements show the JVM-startup overhead matters. To mitigate the per-invocation cost, the framework batches verifications when possible: `strand verify` accepts a single file, so the harness runs one verification per emission. A future "verifier daemon" CLI mode would be the cheaper path; that is a `strand` CLI extension, not a framework extension, and is out of scope here.

The Python package layout:

```
evaluation/dynamic/
├── README.md                  framework documentation
├── pyproject.toml             Python package + dev dependencies
├── strand_eval/
│   ├── __init__.py
│   ├── backends/              EmissionBackend implementations
│   │   ├── anthropic.py       live Anthropic API
│   │   ├── mocked.py          replay from recorded fixtures
│   │   └── base.py            abstract interface
│   ├── languages/             per-baseline verify+execute adapters
│   │   ├── strand.py          shells out to `strand` CLI
│   │   ├── python.py          imports and runs Python in-process
│   │   ├── kotlin.py          stub for Kotlin baseline (deferred)
│   │   └── base.py            abstract interface
│   ├── tasks.py               TaskSpec dataclass + loader
│   ├── prompts.py             SystemPromptBuilder for Strand + baselines
│   ├── loop.py                multi-turn retry loop driver
│   ├── metrics.py             per-run aggregation + statistics
│   ├── recording.py           fixture record/replay
│   └── cli.py                 `strand-eval` console entry point
├── prompts/                   prompt templates, externally editable
│   ├── strand-system.md       Strand grammar + reserved-name reference
│   ├── strand-fewshot.md      worked example or two
│   ├── python-system.md       Python+type-hints framing
│   └── ...                    one per baseline
├── fixtures/                  recorded responses for mocked mode
│   └── <task>/<config>/<model>/<sample-N>.json
├── runs/                      timestamped per-run reports
│   └── 2026-MM-DD-HHMMSS/
│       ├── summary.json
│       ├── per-task.md
│       └── transcripts/
└── tests/                     pytest test suite
    ├── test_loop.py
    ├── test_recording.py
    └── test_prompts.py
```

The framework's Python package is named `strand_eval` to distinguish it from the broader `evaluation/` directory. The CLI entry point (`strand-eval`) is installed via `pip install -e evaluation/dynamic/` and is the operator-facing interface.

### 3.3 Prompt design

The Strand system prompt must convey enough context for an LLM unfamiliar with Strand to emit a valid Layer A program. The current candidate ingredients:

- **The Layer A grammar.** `LayerAGrammar.codes` defines 51 codes (42 base + 9 density sugars + Slice 10's nested form). Each code has a positional-arg schema. A complete listing fits in approximately 200 lines of prose, well under the system-prompt budget of even small-context models.
- **The implicit prelude.** Layer A density v1 reserves 49 names for primitive types, builtins, and effect categories. The prompt lists them with their conventional uses.
- **The density sugars.** IF, WHEN, compact LAM params, inline literals, auto-VarRef, anonymous `_` ids, inline FIELD_LIST, nested `(CODE args...)` expressions. Each is a one-line example.
- **Worked examples.** Two or three end-to-end Layer A programs — factorial (recursion + IF), a JSON value (sum + product), and a small state machine — with both the natural-language statement and the program. These ground the grammar in actual problems.
- **The error format.** A single example of a verifier error so the model knows what feedback shape to expect on retries.

Estimated system-prompt size: ~3,000-5,000 tokens for the Strand baseline. The Python+type-hints prompt is shorter (~500 tokens) because the model already knows Python; it only needs the task-framing convention.

The framework reuses `strand grammar` (Layer B GBNF output) as the constraint grammar when the configuration enables grammar-constrained decoding. The Anthropic API does not currently expose GBNF support at the public-API level, so grammar-constrained generation is **deferred to the first configuration measured against a local model (llama.cpp / vLLM)**; the API-only configurations measure raw text generation. The proposal does not commit to a specific local-model integration in the initial slice; it specifies the seam (`EmissionBackend.with_grammar(gbnf_text)`) and leaves the implementation to a follow-up.

Recommendation: **system prompt = grammar reference + density-sugar cheatsheet + two-shot examples + error-format example**. Externalize the prompt as a Markdown file in `evaluation/dynamic/prompts/` so it can be tuned without code changes. The framework loads it at run start and includes the rendered length in the per-run report (it is part of the token-cost accounting).

The per-task user prompt is the task's `description.md` plus a short instruction line — "Emit a Strand Layer A program that satisfies the requirements above. Output only the Layer A program, no commentary." This convention parallels the static framework's per-task description and is the same description used across baselines.

### 3.4 Retry-loop semantics

Single-turn vs multi-turn is the central design choice. **Single-turn** (one emission, score against the verifier, no retries) gives the cleanest per-emission economics but misses the dynamic-cost question entirely — the verifier-feedback loop is the *whole point*. **Multi-turn** (emit → verifier-fails → revise → repeat until pass-or-cap) is what real agent code does and is what the framework must measure.

Recommendation: **multi-turn, with a cap**. Per-task retry budget: 5 emissions. After 5 failed emissions, the task is recorded as `unconverged` and the partial token cost is attributed. The cap of 5 is a Phase 1 placeholder; the operator can configure it per-run, and the per-task report distinguishes "converged in k retries" from "ran out of budget". The headline metric is `tokens-per-successful-task` averaged over only the converged samples; `convergence-rate` is reported alongside as the second-order number.

Per-turn structure on retry: the framework appends an `assistant` message with the prior emission and a `user` message with the structured verifier error. The verifier error is emitted in a deliberately reader-friendly form (not the raw canonical-dag-json error pretty-print). The CLI's stderr already produces "verification failed: <error name>(<at>, <detail>)" lines per error; the framework reformats these as a short paragraph with the failing node's author id and a brief explanation. The full error JSON is also included for completeness (the model can extract structure from either form). This is a design choice worth measuring — whether prose feedback or JSON feedback yields better convergence — and the framework supports both via a `feedback_format: prose | json | both` configuration.

For the baselines, the equivalent of "verifier feedback" is the language's own correctness signal. Python: stdout of `python program.py` plus the expected output for diffing, or the failing assertion's traceback. Rust: `cargo check` output for static failures, runtime stdout for dynamic. TypeScript: `tsc --strict` then `node`. The framework's `Language` adapter (§3.6) abstracts these.

### 3.5 Success criteria per task

Tasks fall into three shapes by what "correct" means:

- **Pure-value tasks** (factorial, JSON value): the program returns a value; success is `interpreter == expected`.
- **Schema-bearing tasks**: success is `verifier accepts AND interpreter == expected` (the verifier already rejects ill-typed schemas, so the runtime check is the additional gate).
- **State-machine tasks** (toggle): the program is a state machine; success is `runtime over fixed event list emits expected trace`.

The framework supports a per-task `expected.yaml` (or `.json`) block that declares the success-check shape:

```yaml
# evaluation/dynamic/tasks/01-factorial/expected.yaml
check: pure-value
input: []                   # arguments, if the program is a top-level function
expected_output: 120        # for factorial(5)
# alternatively: expected_output_python_repr: "{'a': 1, 'b': [2, 3]}"
```

For state-machine tasks:

```yaml
# evaluation/dynamic/tasks/03-toggle-machine/expected.yaml
check: state-machine
events:
  - {tag: unit}
  - {tag: unit}
  - {tag: unit}
expected_final_state: {tag: bool, value: true}
expected_outputs: []        # toggle emits no outputs per event
```

The framework dispatches on `check`; new check types (e.g., `effect-trace` for tasks that verify effect declarations) are added to `strand_eval.languages.<lang>.check_*` functions.

For Python and other baselines, the check is the same shape against the same expected value: `python program.py` is expected to print the canonical repr of the expected output, or to define a `main()` that returns it. The framework normalizes value comparison across languages (Python `int` ↔ Strand `IntLit`, etc.) via a small adapter.

Recommendation: **per-task `expected.yaml` with three initial check types: `pure-value`, `state-machine`, `schema-validation`**. The framework rejects a task at startup if its `expected.yaml` declares a check type the language adapter does not support — this lets each baseline opt into the subset of the suite it can handle, rather than requiring every baseline to implement every check type.

### 3.6 Baseline comparison

Q-021 names five conventional baselines. The proposal stages their introduction by integration cost:

| Baseline | Verify cost | Run cost | First-class? |
|----------|-------------|----------|--------------|
| Python+type-hints | `mypy --strict program.py` | `python program.py` | Yes, initial slice |
| Strand (canonical dag-json) | `strand verify program.json` | `strand run program.json` | Yes, initial slice |
| Strand (Layer A) | `strand author program.layer-a` (compiles + verifies) | `strand run` on emitted JSON | Yes, initial slice |
| Kotlin Coroutines | `kotlinc program.kt` | `java -jar program.jar` | Deferred |
| Rust | `cargo check` | `cargo run` | Deferred |
| TypeScript-strict | `tsc --strict` | `node program.js` | Deferred |
| SimPy/ShortCoder | Same as Python | Same as Python | Deferred (transpile step needed) |

The initial slice ships three baselines: Python+type-hints, Strand canonical dag-json, and Strand Layer A. This trio answers the most important comparison — Strand vs Python — and the most important intra-Strand comparison — canonical vs Layer A. Kotlin / Rust / TypeScript / SimPy are deferred to a follow-up shipping unit because each adds a toolchain dependency (kotlinc, cargo, node, the SimPy transpiler) that complicates the framework's setup. The `Language` adapter interface is designed so adding a baseline is a single-file addition (a new `strand_eval/languages/<lang>.py`).

The comparison metric is `tokens-per-successful-task`, normalized by the model's tokenizer (counted via the Anthropic API's usage response, or estimated as `bytes / 4` in mocked mode). The geometric mean of per-task ratios is the headline; per-task ratios are also reported because per-task variance is often more diagnostic than the mean.

A subtle point: Python "verification" is not directly comparable to Strand verification. Strand's verifier catches type, effect, and structural errors *before* any execution; `mypy --strict` catches type errors before execution but not effect or invariant errors (Python has no effect system); plain Python catches nothing before execution. The framework reports two convergence rates per baseline: **static-verified-rate** (whatever the language's static checker accepts on first emission) and **execution-success-rate** (the program ran and produced the expected output on first emission). For Strand both rates are effectively the same (the verifier subsumes the static check); for Python+type-hints they differ (mypy passes, but runtime might fail); for plain Python only the second exists.

Recommendation: **three baselines in the initial slice** (Python+type-hints, Strand canonical, Strand Layer A), with the `Language` interface generalizing to the rest. Each baseline supplies its own system prompt template, its own verify command, and its own run command.

### 3.7 Mocking and CI

CI must run the framework end-to-end without API credentials. Three mechanisms together support this.

**Recorded fixtures.** On first run with `--record`, the framework saves every API response to `evaluation/dynamic/fixtures/<task>/<config>/<model>/<sample-N>.json`. The fixture stores the request (system + user messages, model, temperature, sample index) and the response (full content + token usage). On subsequent runs in `--mock` mode, the framework loads the fixture matching the request signature instead of calling the API. Fixtures are committed to the repository; CI runs in `--mock` mode by default.

**Deterministic seeds.** The model API call uses a fixed seed (where supported; Anthropic's API supports a seed parameter for limited determinism on Sonnet/Opus). The recorded fixtures lock the sample for replay; the framework asserts the fixture's recorded prompt matches the live prompt and refuses to use a stale fixture when prompts have changed (which would silently mask real changes).

**A "synthetic" backend for unit tests.** Beyond recorded fixtures (which capture real model behavior), the unit-test suite uses a `SyntheticBackend` that emits scripted responses regardless of input. This lets the framework's tests focus on the loop logic (prompt assembly, retry handling, metrics accounting) without depending on either the API or large fixture files.

Recommendation: **all three mechanisms, with CI running in `--mock` mode against committed fixtures**. The framework's pytest suite uses `SyntheticBackend` for loop-logic tests and `MockedBackend` for end-to-end fixture replay. The first live recording is a manual operator action: `strand-eval record --task all --config recommended --model claude-sonnet-4` runs once and commits the resulting fixtures. Subsequent CI runs replay them.

Fixture freshness is a real concern: recorded fixtures embed the model's behavior at the time of recording, and model behavior drifts between releases. The framework records the model version in each fixture and reports stale fixtures (where the requested model has been deprecated or replaced) as warnings. A periodic re-recording pass — say, monthly or per-major-model-release — keeps fixtures meaningful. This pass is operator work, not framework automation.

### 3.8 Cost and credential management

A representative cost projection (Anthropic Claude Sonnet 4 pricing as of writing: ~$3/Mtok input, ~$15/Mtok output):

- System prompt: ~5,000 tokens (input).
- Per-task per-emission output: ~500 tokens (Strand Layer A) or ~200 tokens (Python).
- Average retries per task: ~3 emissions to converge (conservative estimate from prior agentic-coding benchmarks).
- Total per (task, configuration, sample): ~20,000 input tokens (system prompt × retries) + ~1,500 output tokens.
- Per-(task, config, sample) cost: ~$0.08.

For a Phase 1 run with 10 tasks × 6 configurations × 20 samples × 3 baselines = 3,600 samples, total cost is approximately **$300 per full run**. For development and iteration, a smaller "smoke" run (10 tasks × 3 configs × 3 samples = 90 samples, ~$8) is the default.

Cost reporting is a first-class framework concern. The CLI prints the projected cost before the run starts and asks for confirmation when over a threshold (`--budget $50` flag). Each fixture records the actual token cost; the per-run report aggregates total spend. Operators can scope down a run by `--tasks`, `--configs`, `--samples` flags.

API credentials come from the `ANTHROPIC_API_KEY` environment variable, with an optional `.env` file loaded by `python-dotenv` for local development. The `.env` file is gitignored. A `strand-eval check-credentials` subcommand confirms the key is loadable and makes a minimal test API call (~$0.001) to verify it works before a long run.

Recommendation: **env-var-by-default with optional `.env`, projected-cost-before-confirm, per-fixture token cost tracking**. The framework refuses to start a non-mocked run if `ANTHROPIC_API_KEY` is unset; it prints a clear message about how to set it.

### 3.9 Output integration with the static framework

The static framework's `evaluation/results.md` is settled and the proposal does not touch it. The dynamic framework's outputs go in two places:

**Per-run artifacts.** Each run produces a timestamped directory under `evaluation/dynamic/runs/<timestamp>/` containing:

- `summary.json` — machine-readable aggregate metrics
- `per-task.md` — human-readable table per task × configuration
- `transcripts/<task>-<config>-<sample>.md` — full conversation transcripts for failed cases (configurable; default: failed samples only, since success cases are uninteresting; `--full-transcripts` saves all)

The timestamped runs are partly committed (the `summary.json` of the latest blessed run) and partly gitignored (the per-sample transcripts, which are large). The framework's `.gitignore` rules cover this.

**Aggregate report.** A single committed `evaluation/dynamic-results.md` rolls up the latest blessed run's aggregate numbers in the same shape as `evaluation/results.md`. The two files together — static and dynamic — answer the headline question: `tokens-per-successful-task = static-cost × emissions-per-success`. Each form is reported standalone; the product is the headline.

Recommendation: **per-run timestamped artifacts under `evaluation/dynamic/runs/`, aggregate `evaluation/dynamic-results.md` committed**. The aggregate report follows the same table shape as the static `results.md`: per-task tables plus a final geomean aggregate.

### 3.10 Task suite expansion

The static framework has three tasks; the dynamic framework needs broader coverage to be diagnostic. The proposal recommends a 10-task initial suite, sequenced by complexity. The first three are the existing static tasks (so the static and dynamic frameworks share inputs). The next seven are new.

| # | Task | Strand features exercised | Reference language(s) | Source corpus |
|---|------|--------------------------|----------------------|---------------|
| 01 | Factorial | recursion, Fixpoint, Match | Python, Strand | corpus 21 |
| 02 | JSON value | sum type, schema declaration | Python, Strand | corpus 54 |
| 03 | Toggle machine | state machine, transition function | Python, Strand | corpus 41 |
| 04 | Option unwrap with default | sum + constructor pattern | Python, Strand | corpus 25/26 |
| 05 | Sum a list | recursive type, Fixpoint, Match | Python, Strand | corpus 32 |
| 06 | Counter machine | state machine + output emission | Python, Strand | corpus 46 |
| 07 | Bounded counter (schema) | Schema + Invariant on Int | Python, Strand | corpus 50/51 |
| 08 | NonEmpty list (schema) | Schema + Invariant on recursive | Python, Strand | corpus 53 |
| 09 | File-write under capability | EffectCategory + CapabilityScope | Python, Strand | corpus 17 |
| 10 | Effect handler intercepts logger | Handler (N-043) | Python, Strand | corpus 38 |

The new tasks (04–10) follow the static-task pattern: a `description.md`, a `reference.python.py`, a `reference.canonical-json.json`, and a `reference.layer-a` file. The dynamic framework adds an `expected.yaml` for the success check. The references are drawn verbatim from existing corpus programs where possible (the same convention the static framework already uses).

Tasks 09 and 10 are deliberately Strand-favoring (effects and handlers have no idiomatic Python equivalent; the Python reference must approximate them with try/except or context managers). The headline geomean includes these tasks at face value because the comparison measures *whether Strand wins on the tasks Strand is designed for*; reporting per-task ratios alongside the geomean makes the per-task asymmetry visible.

Recommendation: **10-task initial suite covering the 7 representative shape categories** (pure recursion, sum types, state machines, recursive types, schemas, effects, handlers). The framework loads tasks dynamically from `evaluation/dynamic/tasks/` so adding tasks is a directory-creation pass; tasks declared in `--tasks` are filtered at run start.

The static framework's `tasks/` directory has three entries today. The dynamic framework's `tasks/` directory contains all ten (the first three reuse the static framework's reference files via filesystem links or by reading from `../tasks/<n>-<name>/`). The two frameworks share a tasks model when their inputs overlap.

## 4. Recommended approach

The framework is a Python package (`strand_eval`) under `evaluation/dynamic/` that drives a real LLM through the verifier-feedback retry loop and records per-task token-cost metrics. The four operational layers:

**Layer 1 — Orchestrator (`loop.py`).** The retry-loop driver. Given a task, a configuration (`{language, prompt_template, backend, max_retries, ...}`), and a model identifier, runs the emit → verify → revise cycle until success or budget exhaustion. Records per-emission tokens, per-turn latency, total turns, and the final outcome. The orchestrator is language-agnostic and model-agnostic; it dispatches verify and run through the `Language` adapter and emission through the `EmissionBackend` adapter.

**Layer 2 — Emission backend (`backends/`).** Implements `EmissionBackend.emit(messages: List[Message]) -> EmissionResult`. The Anthropic backend (`backends/anthropic.py`) wraps the official `anthropic` Python SDK. The mocked backend (`backends/mocked.py`) loads recorded fixtures from disk. The synthetic backend (`backends/synthetic.py`) emits scripted responses for unit tests. All three implement the same interface; the orchestrator does not know which is in use.

**Layer 3 — Language adapter (`languages/`).** Implements `Language.verify(source) -> VerifyResult` and `Language.run(source, expected) -> RunResult`. The Strand adapter (`languages/strand.py`) shells out to `strand author --emit-json` → `strand verify` → `strand run` (or `strand machine` for state-machine tasks). The Python adapter (`languages/python.py`) runs `mypy --strict` then imports the program and calls its entry point in a subprocess. Each adapter normalizes its output to the framework's shared result types.

**Layer 4 — Metrics + recording (`metrics.py` + `recording.py`).** Per-emission token counts (from the API's usage response), per-turn latency, per-task convergence rate, geometric-mean aggregation across tasks. Recording captures the full conversation per (task, config, sample) for replay; metrics aggregates blessed-run summaries into `dynamic-results.md`.

The four layers are independently swappable. Switching from the Anthropic API to a different provider is a new file in `backends/`. Adding a baseline is a new file in `languages/`. Adding a metric is a new function in `metrics.py`. The `loop.py` orchestrator is the framework's stable interface.

## 5. Detailed mechanism

### 5.1 Configuration

A run is parameterized by a `RunConfig`:

```python
@dataclass
class RunConfig:
    tasks: list[str]              # task ids, or ["all"]
    configurations: list[str]     # configuration names defined in configs/
    models: list[str]             # e.g., ["claude-sonnet-4", "claude-opus-4"]
    backend: BackendChoice        # "anthropic" | "mocked" | "synthetic"
    samples_per_cell: int         # N for statistical aggregation, default 5
    max_retries: int              # cap per task, default 5
    feedback_format: FeedbackFmt  # "prose" | "json" | "both", default "prose"
    record_fixtures: bool         # save responses to fixtures/
    output_dir: pathlib.Path      # default: evaluation/dynamic/runs/<timestamp>
```

The CLI surface:

```
strand-eval run --config recommended --model claude-sonnet-4 --samples 5
strand-eval run --tasks 01-factorial,02-json-value --backend mocked
strand-eval record --task all --config recommended --model claude-sonnet-4
strand-eval report --run 2026-05-25-143020
strand-eval check-credentials
```

A `Configuration` is a named bundle of (language, prompt template, backend modifier). Six initial configurations match Q-034 §7:

1. `strand-canonical` — Python prompts an LLM to emit canonical dag-json directly
2. `strand-layer-a` — LLM emits Layer A (without elaboration sugars)
3. `strand-layer-a-density-v4` — LLM emits density v4 Layer A (the recommended target)
4. `python-type-hints` — LLM emits Python+type-hints
5. `strand-layer-a-density-v4-grammar` — same as 3, with grammar-constrained decoding (deferred until local-model integration; the slot is registered)
6. `strand-toolcall` — LLM uses Anthropic tool-use to assemble the graph incrementally (deferred to follow-up; slot registered)

The initial slice ships configurations 1, 2, 3, 4. The remaining two are scaffolded but produce a "not yet implemented" message.

### 5.2 The retry loop

The orchestrator's per-task loop in pseudocode:

```python
def run_task(task, config, model, sample_index):
    messages = [
        SystemMessage(content=load_system_prompt(config)),
        UserMessage(content=load_task_prompt(task)),
    ]
    metrics = TaskMetrics(task=task, config=config, sample=sample_index)
    for attempt in range(config.max_retries):
        emission = backend.emit(messages=messages, model=model)
        metrics.record_emission(emission)

        program_source = extract_program(emission.content)
        verify_result = language.verify(program_source)
        if not verify_result.ok:
            messages.append(AssistantMessage(content=emission.content))
            messages.append(UserMessage(content=format_feedback(verify_result, config.feedback_format)))
            continue

        run_result = language.run(program_source, task.expected)
        if not run_result.ok:
            messages.append(AssistantMessage(content=emission.content))
            messages.append(UserMessage(content=format_run_feedback(run_result)))
            continue

        metrics.record_success(attempt)
        return metrics

    metrics.record_unconverged()
    return metrics
```

Three subtleties worth recording:

- **Program extraction.** The model's response may include commentary before/after the program. The framework looks for a fenced code block in the response (` ```layer-a ` for Strand, ` ```python ` for Python); if absent, it treats the entire response as the program. This is a brittle convention; the prompt instructs the model to emit "only the program, in a fenced code block".
- **Feedback formatting.** The Strand adapter's `format_feedback` reads `strand verify` stderr and emits a short prose paragraph naming the failing node and the error type, followed by the structured JSON for completeness. The Python adapter emits `mypy` output or, on runtime failure, the Python traceback. Different formats are an axis of measurement (the `feedback_format` config) — both `prose` and `json` are produced; configurations select which the model sees.
- **Run-result comparison.** The `Language.run` method receives the task's `expected` block and is responsible for comparing the program's output to the expected value. For pure-value tasks this is direct equality; for state-machine tasks it is trace-equivalence; for schema tasks it is "the verifier accepted the program, no run needed". The comparison is per-language because value reprs differ.

### 5.3 The prompts

The Strand system prompt is structured in five sections:

```
# Strand Layer A reference

You are emitting Strand programs in Layer A authoring format. Strand
is a content-addressed graph-based programming language; Layer A is
the compact text projection that compiles to canonical JSON.

## Grammar

A Layer A program is a sequence of lines. The first line is `@v=1 root=<name>`.
Each subsequent line declares one node:

    <author-id> <CODE> <arg>...

Codes are 1-3 uppercase letters. Arguments are positional, per the code's schema.

## Codes

(Generated from LayerAGrammar.codes; one line per code, with arg shape and a short example.)

## Implicit prelude

The following names are pre-bound and may be used without declaration:

    intT, boolT, stringT, unitT, ...     (49 reserved names)

## Density sugars

IF, WHEN, compact LAM params, inline literals, auto-VarRef on PRC binders,
anonymous `_` ids with @last, inline FIELD_LIST, nested (CODE args...).

## Example: factorial

(Worked example with the natural-language statement, Layer A program,
and brief annotation.)

## Errors

If verification fails, you will receive feedback like:

    verification failed:
      at <author-id>: <error-name>(<detail>)

Use the author id to locate the failing node and revise.
```

The prompt is templated and externalized as `evaluation/dynamic/prompts/strand-system.md`. The Python+type-hints prompt is much shorter:

```
You are emitting Python 3 programs with type hints, suitable for
`mypy --strict`. Provide a complete program including a `main()`
function. The program will be checked with mypy and executed; the
output of `main()` will be compared to the expected value.

If mypy or execution fails, you will receive the error and may revise.
```

Other baseline prompts follow the same shape and live in `prompts/<lang>-system.md`.

### 5.4 Metrics

Per-emission metrics: input tokens, output tokens, latency. From the Anthropic API's `usage` response in live mode; reconstructed from fixture metadata in mocked mode.

Per-task per-sample metrics: total emissions, total input + output tokens, convergence-or-not, time-to-convergence, per-emission verifier results (for failure-mode analysis).

Per-(task, config, model) cell metrics: median tokens-per-successful-task across samples, bootstrap 95% CI, convergence rate (fraction of samples that converged), median emissions-to-converge.

Per-configuration metrics: geometric mean of per-task tokens-per-successful-task ratios across the suite. Per-task ratios reported alongside.

The framework writes all of these to `runs/<timestamp>/summary.json`. The `report` subcommand renders the JSON into `per-task.md` and updates `dynamic-results.md` if `--bless` is passed.

A representative `dynamic-results.md` shape (mocked numbers for illustration):

```markdown
# Q-021 dynamic measurement report

Run: 2026-05-25-143020   Model: claude-sonnet-4   Samples: 5
Backend: anthropic        Total cost: $24.50

## 01-factorial

| Configuration | Convergence | Tokens/success | × vs Python |
|---------------|-------------|----------------|-------------|
| Python+type-hints | 5/5 | 1,200 | 1.00 |
| Strand canonical | 3/5 | 8,400 | 7.00 |
| Strand Layer A | 5/5 | 3,200 | 2.67 |
| Strand Layer A density v4 | 5/5 | 2,100 | 1.75 |

(per-task tables for 02, 03, ...)

## Aggregate

| Configuration | Geomean × vs Python | Convergence (all tasks) |
|---------------|---------------------|-------------------------|
| Python+type-hints | 1.00 | 50/50 |
| Strand canonical | 6.50 | 32/50 |
| Strand Layer A | 2.40 | 47/50 |
| Strand Layer A density v4 | 1.45 | 49/50 |
```

The numbers are illustrative; the framework produces real numbers per run. The "× vs Python" column is the headline. The convergence column is the second-order check — a configuration that converges only 30% of the time has a misleadingly low tokens-per-success number that the convergence column reveals.

### 5.5 Recording and replay

A recorded fixture has the shape:

```json
{
  "schema_version": 1,
  "recorded_at": "2026-05-25T14:30:20Z",
  "model": "claude-sonnet-4-20250119",
  "request": {
    "system": "...",
    "messages": [...],
    "temperature": 0.7,
    "seed": 42
  },
  "response": {
    "content": "...",
    "stop_reason": "end_turn",
    "usage": {"input_tokens": 5200, "output_tokens": 480}
  }
}
```

Fixture lookup uses a stable hash over the request fields. When a request matches a fixture's hash, the fixture is replayed. When no fixture matches (e.g., the prompt changed), the framework in `--mock` mode raises `FixtureNotFound(request_hash, fixture_dir)` with the path to record. The framework's `--record` mode saves fixtures for every API call; running `record` after a prompt change re-records the affected cells.

The fixture format is intentionally readable JSON, not VCR cassettes or compressed binary. Operators inspect failing transcripts directly; humans diff fixtures across recordings.

Fixtures live under `evaluation/dynamic/fixtures/<task>/<config>/<model>/<sample-N>.json`. The directory tree is sparse — only cells the operator has explicitly recorded exist. CI runs in `--mock` mode and fails loudly on a fixture miss (rather than silently calling the API), which makes "the framework's tests pass" a meaningful signal.

### 5.6 Strand CLI integration

The framework shells out to `strand` for every Strand operation. The CLI surface is the seam:

- `strand author <file.layer-a> --emit-json` → stdout: dag-json, exit code 0 on success, non-zero with stderr message on parse/elaboration error.
- `strand verify <file.json>` → stdout: `type: <RootType>`, exit code 0 on success, non-zero with stderr message on verifier error.
- `strand run <file.json> [--grant-all]` → stdout: `value: <Value>`, exit code 0 on success, non-zero on runtime error.
- `strand machine <file.json> --events <events.json>` → stdout: trace, exit code 0 on success.

The Python adapter parses CLI output as it currently exists. The CLI does not need any changes for the initial integration; the framework is a downstream consumer of the existing interface. A future "verifier daemon" mode (one long-running JVM serving repeated verify calls over stdin/stdout) is a useful follow-up but is out of scope here.

The framework handles the `strand` binary path via configuration (a `strand_eval` config file pointing at `impl/cli/build/install/cli/bin/cli` or the system-installed binary), defaulting to the build output path when run from the project root. A `strand-eval check-strand-cli` subcommand confirms the binary is callable.

## 6. Cost analysis

The per-(task, config, sample) cost is dominated by the input-token bill: the Strand system prompt is ~5,000 tokens, multiplied by the number of retries (~3 average), giving ~15,000 input tokens per task. At Sonnet pricing (~$3/Mtok input, ~$15/Mtok output):

- Per (task, config, sample): ~$0.06 input + ~$0.02 output ≈ **$0.08**
- Smoke run (10 tasks × 3 configs × 3 samples): 90 cells ≈ **$7**
- Phase 1 default run (10 tasks × 4 configs × 5 samples): 200 cells ≈ **$16**
- Full Q-021 run (10 tasks × 6 configs × 20 samples × 3 baselines): 3,600 cells ≈ **$290**

These projections assume Sonnet; Opus is ~5× more expensive, Haiku is ~5× cheaper. The framework's `--model` flag makes the model trade-off explicit per run.

Caching has substantial leverage: Anthropic's prompt caching reduces the input cost of repeated system prompts by ~10×. The framework opts into prompt caching automatically because every retry uses the same system prompt; the marginal cost of a retry is dominated by the new user message and the output. With caching enabled, the per-task input cost drops by ~5× (the first emission's input is full-priced; subsequent retries are mostly cached).

The aggregate cost projections drop accordingly: smoke run ~$2, Phase 1 default ~$4, full Q-021 run ~$60.

The framework reports projected and actual cost per run; operators can budget per-run with `--budget` and abort early if the running cost exceeds the limit.

## 7. Evaluation criteria

The framework is successful in its initial slice if:

- **Smoke tests pass in CI without credentials.** A pre-recorded smoke fixture set runs end-to-end through `strand-eval run --backend mocked`; CI passes; the per-task metrics match a committed expected report (within tolerance for ordering/stable-sort).
- **A live run produces a populated `dynamic-results.md`.** Operating with a real Anthropic API key, running the Phase 1 default configuration, produces a report with non-trivial numbers for the four initial configurations across the 10-task suite.
- **Cost projection matches actual cost within 30%.** The framework's pre-run budget projection is calibrated.
- **At least one task shows a measurable Strand-vs-Python tokens-per-success ratio.** This is the first real data point on the central question; the magnitude is less important than the data existing.

A second-tier success: by the end of Phase 1, the framework supports the full Q-021 baseline set (the four deferred languages — Kotlin, Rust, TypeScript, SimPy — each landing as one `Language` adapter per shipping unit) and produces the headline geomean ratio against the conventional-baseline geomean. The Q-021 hypothesis is **supported** if Strand Layer A density v4's tokens-per-successful-task across the 10-task suite comes in below 1.3× the geometric mean of the five conventional-language baselines, **with appropriate convergence rates** (a configuration that wins on token count but converges only half as often is not a clean win).

This evaluation criterion is itself a research question: what counts as "appropriate convergence"? The proposal's answer is that the headline must report both numbers — tokens-per-success and convergence-rate — and that a configuration is "winning" only when it wins on both or wins on one without significantly losing on the other. The exact tolerance is a Phase 1 calibration concern, not specified here.

## 8. Tradeoffs and open questions

**Deferred intentionally:**

- **Grammar-constrained decoding via the Anthropic API.** Anthropic does not publicly expose GBNF-grammar-constrained generation. The framework supports the configuration slot (`strand-layer-a-density-v4-grammar`) and will measure it as soon as a local-model backend (llama.cpp / vLLM) is integrated. The initial slice measures raw text generation only.
- **Tool-call assembly (Q-034 §3.6).** The Anthropic tool-use API maps naturally onto a "construct the graph incrementally" interface, but the prompt engineering and per-call cost accounting are substantively different from raw text generation. Deferred to a follow-up shipping unit; the configuration slot is scaffolded.
- **The four non-Strand baselines beyond Python.** Kotlin, Rust, TypeScript-strict, SimPy/ShortCoder each need a `Language` adapter, a system prompt template, and toolchain setup. Each is a clean follow-up addition with no framework changes; sequenced by integration cost.
- **Local-model backends.** llama.cpp / vLLM / Ollama integration would let operators run the framework without API costs and would unlock the grammar-constrained-decoding configuration. The `EmissionBackend` abstraction is designed for this; the actual integration is one new file in `backends/`.
- **A "verifier daemon" CLI mode.** A long-running `strand` process serving repeated verify calls over stdin/stdout would eliminate the JVM-startup-per-call cost. Useful when the framework grows to thousands of tasks, but unnecessary at the 10-task initial slice; the JVM cost is small relative to the API cost.
- **Stochastic baseline calibration.** Each run uses a fixed seed where supported, but real model output is stochastic. The framework's per-cell sample count is 5 by default; bootstrap confidence intervals across samples are reported. A future "deeper sampling" mode for headline reporting (N=50) would tighten the CIs at the cost of API spend.
- **Effect-declaration-accuracy and capability-minimization metrics.** Q-021 names these among its seven metrics. They require runtime introspection of what effects a Strand program actually exercises (the framework already has this via the runtime's `EffectInstance` recording) and a comparison against what the program declared. The initial slice records the data but does not compute the metric; computation lands in a follow-up.

**Could also (alternatives flagged):**

- **JNI / JPype direct integration with Kotlin classes** instead of shell-out. The cost is engineering complexity; the benefit is per-call latency. Revisit if the framework's runtime is dominated by JVM-startup overhead in measurement.
- **A single committed fixture per (task, config, model) instead of per-sample.** Reduces repository size at the cost of variance estimation accuracy. The current per-sample design keeps statistical discipline visible.
- **Use a single configuration file (YAML/TOML) for tasks + configs + models** instead of the per-task directory pattern. The per-task directory is consistent with the static framework's existing layout; a single config file is more compact but breaks that consistency.
- **A different language for the harness (Rust, TypeScript).** Python's only real competition is Rust if the harness needs to embed the Strand compiler eventually. The current shell-out design defers that question.

**Real research questions:**

- **Does verifier feedback actually accelerate convergence?** The hypothesis is that structured error feedback from the Strand verifier yields faster retry convergence than the equivalent Python loop (mypy errors + runtime tracebacks). This is the first thing the framework measures, and the answer determines whether the verifier's structural-correctness contract pays for itself.
- **What feedback format is best?** Prose vs structured JSON vs both. Within "both", how should the prose summarize the JSON without losing information? The framework supports the comparison as a configuration axis; the answer will inform the prompt template.
- **How sensitive is the result to model choice?** Sonnet vs Opus vs Haiku. Each is a separate cost point. The framework allows the comparison; the answer is a Phase 1 measurement.
- **What is the right retry-budget cap?** 5 retries is a placeholder. Real agent loops sometimes allow many more. The framework records the per-retry trajectory so the optimal cap can be back-derived from the data.
- **Cross-model fixture-replay validity.** A fixture recorded on Sonnet does not generalize to Opus. The framework records the model per fixture and reports mismatches, but the operator must re-record per-model. A future "model-family" abstraction (cell parameterized by model family rather than exact model version) could amortize this.
- **Prompt-engineering productivity.** The system prompt is a substantial artifact (~5,000 tokens) and is the single biggest leverage point in the framework's results. The framework treats it as an external file so it can be tuned without code changes, but does not specify a prompt-engineering protocol. A "prompt A/B test" mode would let operators compare two prompt variants on the same task suite.

## 9. References

**Outgoing references:**
- [`00-motivation.md`](../00-motivation.md) — the AI-first framing this framework's measurements validate
- [`research-plan.md`](../research-plan.md) — Phase 1 evaluation methodology; Q-021's metrics and baselines
- [`evaluation/README.md`](../evaluation/README.md) — the static-cost MVP framework this proposal extends
- [`evaluation/results.md`](../evaluation/results.md) — the static-cost numbers the dynamic-cost framework's headline number multiplies
- [`proposals/implemented/llm-authoring-layer.md`](implemented/llm-authoring-layer.md) — Q-034 §7 "Evaluation framework" sketch that this proposal makes concrete
- [`proposals/implemented/layer-a-density.md`](implemented/layer-a-density.md) — Layer A density v4 results the dynamic framework now needs to validate dynamically
- [`open-questions.md`](../open-questions.md) — Q-020 (corpus bootstrap), Q-021 (metrics and baselines), Q-034 (authoring layer)
- [`impl/CLAUDE.md`](../impl/CLAUDE.md) — Strand CLI surface the framework shells out to
- [`decisions/ADR-001-graph-not-text.md`](../decisions/ADR-001-graph-not-text.md) — graph-native source; the framework respects this by treating Layer A as the agent-facing surface and dag-json as the verifier's input
- [`decisions/ADR-002-no-human-projection.md`](../decisions/ADR-002-no-human-projection.md) — the framework writes/reads Layer A as the agent-facing surface, not a human-readable projection

**Incoming references:**
- [`proposals/README.md`](README.md)
