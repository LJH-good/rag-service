-- application-local-*.yml 의 hibernate.default_schema=rag 에 맞춰 스키마만 미리 만들어 둔다.
-- 테이블 자체는 Hibernate ddl-auto 로 생성한다(마이그레이션 도구 없음).
CREATE SCHEMA IF NOT EXISTS rag AUTHORIZATION rag_user;

-- RagDocument.userType 이 columnDefinition="rag.user_type" 으로 고정 참조하는 네이티브 enum.
-- Hibernate ddl-auto 는 enum 타입 자체는 만들지 않으므로 미리 만들어 둬야 한다.
CREATE TYPE rag.user_type AS ENUM ('admin', 'user');
