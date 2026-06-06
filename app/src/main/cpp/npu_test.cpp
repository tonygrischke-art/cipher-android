// Standalone NPU test — direct NeuroPilot API call
// Compile with NDK, push to device, run via adb shell
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <android/log.h>

#define LOGI(fmt,...) __android_log_print(ANDROID_LOG_INFO,"NpuTest",fmt,##__VA_ARGS__)
#define LOGE(fmt,...) __android_log_print(ANDROID_LOG_ERROR,"NpuTest",fmt,##__VA_ARGS__)

// Minimal NeuronAdapter types
struct NeuronModel;
struct NeuronCompilation;
struct NeuronExecution;

typedef int (*PFN_Model_create)(NeuronModel**);
typedef void (*PFN_Model_free)(NeuronModel*);
typedef int (*PFN_Model_finish)(NeuronModel*);
typedef int (*PFN_Model_addOperand)(NeuronModel*, const void* type);
typedef int (*PFN_Model_setOperandValue)(NeuronModel*, int32_t, const void*, size_t);
typedef int (*PFN_Model_identifyInputsAndOutputs)(NeuronModel*, uint32_t, const uint32_t*, uint32_t, const uint32_t*);
typedef int (*PFN_Model_relaxFP32)(NeuronModel*, bool);
typedef int (*PFN_Compilation_createV2)(NeuronModel*, int, const char*, NeuronCompilation**);
typedef void (*PFN_Compilation_free)(NeuronCompilation*);
typedef int (*PFN_Compilation_finish)(NeuronCompilation*);
typedef int (*PFN_Compilation_setOptString)(NeuronCompilation*, const char*);
typedef int (*PFN_Execution_create)(NeuronCompilation*, NeuronExecution**);
typedef void (*PFN_Execution_free)(NeuronExecution*);
typedef int (*PFN_Execution_setInput)(NeuronExecution*, int32_t, const void*, const void*, size_t);
typedef int (*PFN_Execution_setOutput)(NeuronExecution*, int32_t, const void*, void*, size_t);
typedef int (*PFN_Execution_compute)(NeuronExecution*);

// Operand type struct from NeuronAdapter.h
struct NeuronOperandType {
    int32_t type;
    uint32_t dimensionCount;
    const uint32_t* dimensions;
    float scale;
    int32_t zeroPoint;
};

#define NEURON_NO_ERROR 0
#define NEURON_TENSOR_INT32 4
#define NEURON_TENSOR_FLOAT32 3
#define COMPILATION_TYPE_NORMAL 0

int main(int argc, char** argv) {
    if (argc < 4) {
        printf("Usage: %s <model_path> <cache_dir> <prompt>\n", argv[0]);
        return 1;
    }
    const char* model_path = argv[1];
    const char* cache_dir = argv[2];
    const char* prompt = argv[3];

    LOGI("=== NPU Direct Test ===");
    LOGI("Model: %s", model_path);
    LOGI("Prompt: %s", prompt);

    // 1. Load adapter library
    void* handle = dlopen("libneuronusdk_adapter.mtk.so", RTLD_LAZY);
    if (!handle) {
        LOGI("Bundled adapter not found, trying system paths...");
        handle = dlopen("/vendor/lib64/libneuronusdk_adapter.mtk.so", RTLD_LAZY);
        if (!handle) {
            handle = dlopen("/system/lib64/libneuronusdk_adapter.mtk.so", RTLD_LAZY);
        }
    }
    if (!handle) {
        LOGE("FAIL: Cannot load libneuronusdk_adapter.mtk.so: %s", dlerror());
        return 1;
    }
    LOGI("OK: libneuronusdk_adapter.mtk.so loaded");

    // 2. Resolve symbols
    PFN_Model_create            fnModelCreate;
    PFN_Model_free              fnModelFree;
    PFN_Model_finish            fnModelFinish;
    PFN_Model_addOperand        fnModelAddOperand;
    PFN_Model_setOperandValue   fnModelSetOperandValue;
    PFN_Model_identifyInputsAndOutputs fnModelIO;
    PFN_Model_relaxFP32         fnModelRelax;
    PFN_Compilation_createV2    fnCompCreate;
    PFN_Compilation_free        fnCompFree;
    PFN_Compilation_finish      fnCompFinish;
    PFN_Compilation_setOptString fnCompSetOpt;
    PFN_Execution_create        fnExecCreate;
    PFN_Execution_free          fnExecFree;
    PFN_Execution_setInput      fnExecSetInput;
    PFN_Execution_setOutput     fnExecSetOutput;
    PFN_Execution_compute       fnExecCompute;

    #define LOAD(fn) do { fn = (PFN_##fn)dlsym(handle, #fn); if (!fn) { LOGE("FAIL: dlsym(%s): %s", #fn, dlerror()); return 1; } } while(0)
    LOAD(Model_create);
    LOAD(Model_free);
    LOAD(Model_finish);
    LOAD(Model_addOperand);
    LOAD(Model_setOperandValue);
    LOAD(Model_identifyInputsAndOutputs);
    LOAD(Model_relaxFP32);
    LOAD(Compilation_createV2);
    LOAD(Compilation_free);
    LOAD(Compilation_finish);
    LOAD(Compilation_setOptString);
    LOAD(Execution_create);
    LOAD(Execution_free);
    LOAD(Execution_setInput);
    LOAD(Execution_setOutput);
    LOAD(Execution_compute);
    #undef LOAD
    LOGI("OK: All Neuron API symbols resolved");

    // 3. Load model binary
    FILE* f = fopen(model_path, "rb");
    if (!f) {
        LOGE("FAIL: Cannot open model file: %s", model_path);
        return 1;
    }
    fseek(f, 0, SEEK_END);
    long model_size = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t* model_data = (uint8_t*)malloc(model_size);
    fread(model_data, 1, model_size, f);
    fclose(f);
    LOGI("OK: Loaded model binary (%ld bytes)", model_size);

    // 4. Create model
    NeuronModel* model = NULL;
    int ret = fnModelCreate(&model);
    if (ret != NEURON_NO_ERROR || !model) {
        LOGE("FAIL: NeuronModel_create: %d", ret);
        return 1;
    }
    LOGI("OK: NeuronModel created");

    // 5. Add input operand (int32[1,512])
    uint32_t in_dims[] = {1, 512};
    struct NeuronOperandType in_type = { NEURON_TENSOR_INT32, 2, in_dims, 0.0f, 0 };
    ret = fnModelAddOperand(model, &in_type);
    if (ret != NEURON_NO_ERROR) { LOGE("FAIL: addOperand(input): %d", ret); return 1; }

    // 6. Add output operand (int32[1,512])
    uint32_t out_dims[] = {1, 512};
    struct NeuronOperandType out_type = { NEURON_TENSOR_INT32, 2, out_dims, 0.0f, 0 };
    ret = fnModelAddOperand(model, &out_type);
    if (ret != NEURON_NO_ERROR) { LOGE("FAIL: addOperand(output): %d", ret); return 1; }

    // 7. Add weight operand from model binary
    uint32_t w_dims[] = {1, (uint32_t)(model_size / sizeof(float))};
    struct NeuronOperandType w_type = { NEURON_TENSOR_FLOAT32, 2, w_dims, 0.0f, 0 };
    ret = fnModelAddOperand(model, &w_type);
    if (ret != NEURON_NO_ERROR) { LOGE("FAIL: addOperand(weight): %d", ret); return 1; }
    ret = fnModelSetOperandValue(model, 2, model_data, model_size);
    if (ret != NEURON_NO_ERROR) { LOGE("FAIL: setOperandValue(weight): %d", ret); return 1; }

    // 8. Identify I/O
    uint32_t inputs[] = {0};
    uint32_t outputs[] = {1};
    ret = fnModelIO(model, 1, inputs, 1, outputs);
    if (ret != NEURON_NO_ERROR) { LOGE("FAIL: identifyInputsAndOutputs: %d", ret); return 1; }

    // 9. Relax FP32→FP16
    fnModelRelax(model, true);

    // 10. Finish model
    ret = fnModelFinish(model);
    if (ret != NEURON_NO_ERROR) { LOGE("FAIL: Model_finish: %d", ret); return 1; }
    LOGI("OK: Model built and finished");

    // 11. Compile with MDLA flags
    const char* opt_flags =
        "--relax-fp32 --opt 3 --opt-footprint --opt-accuracy "
        "--mdla-mlo --mdla-conv-exp 1 --mem-opt 3 --stable-linearize "
        "--l1-size-kb 7168 --num-mdla 4 --mdla-flash-attention-mode 0 "
        "--fc-to-conv --mdla-broadcast-act-wgt 1 --broadcast-flow-distance 63 "
        "--mdla-set-conv-xy-split-ic-threshold 99999 "
        "--gno LTS,Inception --gno-exp --gno-non-4d-tiling";

    NeuronCompilation* compilation = NULL;
    ret = fnCompCreate(model, COMPILATION_TYPE_NORMAL, opt_flags, &compilation);
    if (ret != NEURON_NO_ERROR || !compilation) {
        LOGE("FAIL: Compilation_createV2: %d", ret);
        return 1;
    }
    fnCompSetOpt(compilation, opt_flags);
    LOGI("OK: Compilation created, finishing (this takes 60-90s on first run)...");

    ret = fnCompFinish(compilation);
    if (ret != NEURON_NO_ERROR) {
        LOGE("FAIL: Compilation_finish: %d", ret);
        return 1;
    }
    LOGI("OK: Model compiled on NPU!");

    // 12. Cache compiled network
    // (skip for now — just test inference)

    // 13. Create execution
    NeuronExecution* execution = NULL;
    ret = fnExecCreate(compilation, &execution);
    if (ret != NEURON_NO_ERROR || !execution) {
        LOGE("FAIL: Execution_create: %d", ret);
        return 1;
    }
    LOGI("OK: Execution created");

    // 14. Set input (tokenize prompt as UTF-8 codepoints)
    int32_t in_buf[512];
    memset(in_buf, 0, sizeof(in_buf));
    int token_count = 0;
    for (const char* p = prompt; *p && token_count < 512; ) {
        unsigned char c = (unsigned char)*p;
        if (c < 0x80) { in_buf[token_count++] = (int32_t)c; p++; }
        else { in_buf[token_count++] = (int32_t)c + 0x100; p += 2; }  // simplified
    }
    LOGI("Input tokens: %d", token_count);

    struct NeuronOperandType in_type2 = { NEURON_TENSOR_INT32, 2, in_dims, 0.0f, 0 };
    ret = fnExecSetInput(execution, 0, &in_type2, in_buf, 512 * sizeof(int32_t));
    if (ret != NEURON_NO_ERROR) { LOGE("FAIL: setInput: %d", ret); return 1; }

    // 15. Set output
    int32_t out_buf[512];
    memset(out_buf, 0, sizeof(out_buf));
    struct NeuronOperandType out_type2 = { NEURON_TENSOR_INT32, 2, out_dims, 0.0f, 0 };
    ret = fnExecSetOutput(execution, 0, &out_type2, out_buf, 512 * sizeof(int32_t));
    if (ret != NEURON_NO_ERROR) { LOGE("FAIL: setOutput: %d", ret); return 1; }

    // 16. Compute
    LOGI("Running NPU inference...");
    ret = fnExecCompute(execution);
    if (ret != NEURON_NO_ERROR) {
        LOGE("FAIL: Execution_compute: %d", ret);
        return 1;
    }
    LOGI("OK: NPU inference complete!");

    // 17. Read output
    printf("=== OUTPUT TOKENS ===\n");
    for (int i = 0; i < 512; i++) {
        if (out_buf[i] == 0) break;
        if (out_buf[i] >= 32 && out_buf[i] < 127)
            printf("%c", (char)out_buf[i]);
    }
    printf("\n=== END ===\n");

    // Cleanup
    fnExecFree(execution);
    fnCompFree(compilation);
    fnModelFree(model);
    free(model_data);
    dlclose(handle);

    LOGI("=== NPU TEST PASSED ===");
    return 0;
}
