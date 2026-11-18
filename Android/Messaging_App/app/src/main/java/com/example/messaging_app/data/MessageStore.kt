package com.example.messaging_app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MessageStore(context: Context) {

    private val file = File(context.filesDir, "chat_messages.json")
    private val peersFile = File(context.filesDir, "chat_peers.json")

    private val _messages = MutableStateFlow(loadMessages())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _peers = MutableStateFlow(loadPeers())
    val peers: StateFlow<List<PeerContact>> = _peers.asStateFlow()

    fun messagesFor(conversationId: String): List<ChatMessage> {
        return _messages.value.filter { it.conversationId == conversationId }
            .sortedBy { it.timestamp }
    }

    fun upsertMessage(message: ChatMessage) {
        _messages.update { current ->
            val without = current.filterNot { it.id == message.id }
            (without + message).sortedBy { it.timestamp }.also { persistMessages(it) }
        }
    }

    fun updateStatus(messageId: String, status: MessageStatus) {
        _messages.update { current ->
            current.map {
                if (it.id == messageId) it.copy(status = status) else it
            }.also { persistMessages(it) }
        }
    }

    fun upsertPeer(peer: PeerContact) {
        _peers.update { current ->
            val existing = current.find { it.id == peer.id }
            val merged = if (existing == null) {
                peer
            } else {
                existing.copy(
                    displayName = peer.displayName.ifBlank { existing.displayName },
                    lastSeen = maxOf(existing.lastSeen, peer.lastSeen),
                    lastMessagePreview = peer.lastMessagePreview.ifBlank { existing.lastMessagePreview },
                    unreadCount = peer.unreadCount
                )
            }
            (current.filterNot { it.id == peer.id } + merged)
                .sortedByDescending { it.lastSeen }
                .also { persistPeers(it) }
        }
    }

    fun clearUnread(peerId: String) {
        _peers.update { current ->
            current.map {
                if (it.id == peerId) it.copy(unreadCount = 0) else it
            }.also { persistPeers(it) }
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
        persistMessages(emptyList())
    }

    fun clearAllChat() {
        clearMessages()
        _peers.value = emptyList()
        persistPeers(emptyList())
    }

    private fun loadMessages(): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        ChatMessage(
                            id = obj.getString("id"),
                            conversationId = obj.getString("conversationId"),
                            senderId = obj.getString("senderId"),
                            senderName = obj.getString("senderName"),
                            text = obj.getString("text"),
                            timestamp = obj.getLong("timestamp"),
                            isMine = obj.getBoolean("isMine"),
                            status = MessageStatus.valueOf(obj.getString("status"))
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun persistMessages(messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.takeLast(500).forEach { msg ->
            array.put(
                JSONObject()
                    .put("id", msg.id)
                    .put("conversationId", msg.conversationId)
                    .put("senderId", msg.senderId)
                    .put("senderName", msg.senderName)
                    .put("text", msg.text)
                    .put("timestamp", msg.timestamp)
                    .put("isMine", msg.isMine)
                    .put("status", msg.status.name)
            )
        }
        file.writeText(array.toString())
    }

    private fun loadPeers(): List<PeerContact> {
        if (!peersFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(peersFile.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        PeerContact(
                            id = obj.getString("id"),
                            displayName = obj.getString("displayName"),
                            lastSeen = obj.getLong("lastSeen"),
                            lastMessagePreview = obj.optString("lastMessagePreview"),
                            unreadCount = obj.optInt("unreadCount")
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun persistPeers(peers: List<PeerContact>) {
        val array = JSONArray()
        peers.forEach { peer ->
            array.put(
                JSONObject()
                    .put("id", peer.id)
                    .put("displayName", peer.displayName)
                    .put("lastSeen", peer.lastSeen)
                    .put("lastMessagePreview", peer.lastMessagePreview)
                    .put("unreadCount", peer.unreadCount)
            )
        }
        peersFile.writeText(array.toString())
    }
}
