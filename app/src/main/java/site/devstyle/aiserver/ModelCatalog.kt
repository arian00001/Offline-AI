package site.devstyle.aiserver

data class ModelChoice(val id: String, val title: String, val detail: String, val file: String, val url: String)

object ModelCatalog {
    val models = listOf(
        ModelChoice("qwen3-4b", "Qwen3 4B · Q4_K_M", "Balanced · ~2.5 GB · Recommended", "Qwen3-4B-Q4_K_M.gguf", "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf"),
        ModelChoice("qwen3-1.7b", "Qwen3 1.7B · Q4_K_M", "Lighter · faster on limited RAM", "Qwen3-1.7B-Q4_K_M.gguf", "https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"),
        ModelChoice("qwen3-0.6b", "Qwen3 0.6B · Q4_K_M", "Smallest · best for quick smoke tests", "Qwen3-0.6B-Q4_K_M.gguf", "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf")
    )
}
