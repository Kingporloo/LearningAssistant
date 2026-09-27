CREATE CONSTRAINT rag_chunk_scope IF NOT EXISTS
FOR (chunk:RagChunk) REQUIRE (chunk.user_id, chunk.chunk_id) IS UNIQUE;

CREATE CONSTRAINT memory_scope IF NOT EXISTS
FOR (memory:LongMemory) REQUIRE (memory.user_id, memory.memory_id) IS UNIQUE;

CREATE CONSTRAINT memory_entity_scope IF NOT EXISTS
FOR (entity:MemoryEntity) REQUIRE (entity.user_id, entity.name) IS UNIQUE;
