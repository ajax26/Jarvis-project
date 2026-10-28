package com.doorskorea.singkro.ai

/**
 * Wake word "씽씽" in recognizer text (the SingTree sample's rule). Used with the platform recognizer, which
 * transcribes Korean well enough to also take the request said in the same breath: "씽씽, 오늘 무슨 요일이야?"
 */
object WakePhrase {
    // spelling variants the recognizer produces; not "싱싱해/싱싱한..." (fresh), which is an ordinary word
    private val WAKE = Regex(
        "(씽\\s*씽|싱\\s*싱|씽\\s*싱|싱\\s*씽|씬\\s*씬|신\\s*신|sing\\s*sing)(?!\\s*(하|한|해|했|함|히|할))\\s*(아|야|이|님)?[\\s,.!?~]*",
        RegexOption.IGNORE_CASE,
    )

    /** null if [text] has no wake word, else what was said after it ("" for the wake word alone). */
    fun after(text: String): String? = WAKE.find(text)?.let { text.substring(it.range.last + 1).trim() }
}
