package site.devstyle.aiserver

object NativeLlm {
    init { System.loadLibrary("devstyle_llama") }
    external fun loadModel(path: String): Boolean
    external fun unloadModel()
    external fun generate(prompt: String, callback: TokenCallback?, maxTokens: Int): String
    external fun cancel()
    interface TokenCallback { fun onToken(token: String) }
}
