/*
 * 最小 librime JNI 封装（类名与方法签名必须与 librime_jni.so 的
 * Java_com_osfans_trime_core_Rime_* 符号一一对应，来源于同文输入法 trime v3.3.13）。
 * 仅声明本项目用到的接口；native 层回调 handleRimeMessage 转发给监听者。
 */
package com.osfans.trime.core

import java.util.concurrent.CopyOnWriteArrayList

class Rime private constructor() {

    companion object {
        /** native 回调监听（部署通知等），参数为 (type, params) */
        private val messageListeners = CopyOnWriteArrayList<(Int, Array<Any>) -> Unit>()

        fun addMessageListener(l: (Int, Array<Any>) -> Unit) {
            if (!messageListeners.contains(l)) messageListeners.add(l)
        }

        fun removeMessageListener(l: (Int, Array<Any>) -> Unit) {
            messageListeners.remove(l)
        }

        init {
            System.loadLibrary("rime_jni")
        }

        // init
        @JvmStatic
        external fun startupRime(
            sharedDir: String,
            userDir: String,
            versionName: String,
            fullCheck: Boolean,
        )

        @JvmStatic
        external fun exitRime()

        @JvmStatic
        external fun deployRimeSchemaFile(schemaFile: String): Boolean

        @JvmStatic
        external fun deployRimeConfigFile(
            fileName: String,
            versionKey: String,
        ): Boolean

        @JvmStatic
        external fun syncRimeUserData(): Boolean

        // input
        @JvmStatic
        external fun processRimeKey(
            keycode: Int,
            mask: Int,
        ): Boolean

        @JvmStatic
        external fun commitRimeComposition(): Boolean

        @JvmStatic
        external fun clearRimeComposition()

        // output
        @JvmStatic
        external fun getRimeCommit(): CommitProto

        @JvmStatic
        external fun getRimeContext(): ContextProto

        @JvmStatic
        external fun getRimeStatus(): StatusProto

        @JvmStatic
        external fun getRimeResponse(pagingMode: Boolean): RimeResponse

        // runtime options
        @JvmStatic
        external fun setRimeOption(option: String, value: Boolean)

        @JvmStatic
        external fun getRimeOption(option: String): Boolean

        @JvmStatic
        external fun getRimeSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun getCurrentRimeSchema(): String

        @JvmStatic
        external fun selectRimeSchema(schemaId: String): Boolean

        @JvmStatic
        external fun simulateRimeKeySequence(keySequence: String): Boolean

        @JvmStatic
        external fun getRimeRawInput(): String

        @JvmStatic
        external fun getRimeCaretPos(): Int

        @JvmStatic
        external fun setRimeCaretPos(caretPos: Int)

        @JvmStatic
        external fun selectRimeCandidate(index: Int, global: Boolean): Boolean

        @JvmStatic
        external fun deleteRimeCandidate(index: Int, global: Boolean): Boolean

        @JvmStatic
        external fun changeRimeCandidatePage(backward: Boolean): Boolean

        @JvmStatic
        external fun getRimeCandidates(
            startIndex: Int,
            limit: Int,
        ): Array<CandidateProto>

        /** native → Java 消息回调（部署进度/通知等），由 librime_jni 调用 */
        @JvmStatic
        fun handleRimeMessage(
            type: Int,
            params: Array<Any>,
        ) {
            for (l in messageListeners) {
                try { l(type, params) } catch (_: Exception) {}
            }
        }
    }
}
