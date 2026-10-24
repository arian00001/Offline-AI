package site.devstyle.aiserver

data class ModelChoice(val id: String, val title: String, val detail: String, val file: String, val url: String)

object ModelCatalog {
    val models = listOf(
        ModelChoice("qwen3-0.6b", "Qwen3 0.6B · Q4_K_M", "Fastest starter · simple chat and quick tasks · ~400 MB", "Qwen3-0.6B-Q4_K_M.gguf", "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf"),
        ModelChoice("qwen3-1.7b", "Qwen3 1.7B · Q4_K_M", "General assistant · learning and Banglish · ~1.2 GB", "Qwen3-1.7B-Q4_K_M.gguf", "https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"),
        ModelChoice("qwen3-4b", "Qwen3 4B · Q4_K_M", "Stronger general answers · needs more RAM · ~2.5 GB", "Qwen3-4B-Q4_K_M.gguf", "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf"),
        ModelChoice("qwen25-coder-1.5b", "Qwen2.5-Coder 1.5B · Q4_K_M", "Coding helper · explain, debug and write code · ~1 GB", "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf", "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"),
        ModelChoice("qwen25-1.5b-learn", "Qwen2.5 1.5B Instruct · Q4_K_M", "Study helper · step-by-step explanations · ~1 GB", "qwen2.5-1.5b-instruct-q4_k_m.gguf", "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf")
    )
}
