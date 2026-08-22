package com.yang136.sshhelper.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SnippetRepository(private val database: AppDatabase) {
    val snippets: Flow<List<CommandSnippet>> = database.commandSnippetDao().observeAll().map { list ->
        list.map(CommandSnippetEntity::toModel)
    }

    fun forHost(hostId: Long): Flow<List<CommandSnippet>> =
        database.commandSnippetDao().observeForHost(hostId).map { list -> list.map(CommandSnippetEntity::toModel) }

    suspend fun get(id: Long): CommandSnippet? = database.commandSnippetDao().get(id)?.toModel()

    suspend fun save(snippet: CommandSnippet): Long {
        snippet.validationError()?.let(::error)
        val dao = database.commandSnippetDao()
        val existing = if (snippet.id == 0L) null else dao.get(snippet.id)
        return if (existing == null) dao.insert(snippet.toEntity()) else {
            dao.update(snippet.toEntity(existing))
            snippet.id
        }
    }

    suspend fun delete(snippet: CommandSnippet) {
        database.commandSnippetDao().get(snippet.id)?.let { database.commandSnippetDao().delete(it) }
    }
}
