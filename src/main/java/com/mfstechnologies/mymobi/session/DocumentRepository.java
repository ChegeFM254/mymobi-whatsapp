package com.mfstechnologies.mymobi.session;

import com.mfstechnologies.mymobi.model.StoredDocument;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * WORKSTREAM C (persistence): Spring Data JPA repository backing
 * DocumentStore. StoredDocument's @Id is token, so this is a
 * JpaRepository<StoredDocument, String> - findById/deleteById key on
 * the token directly, matching exactly how the in-memory
 * ConcurrentHashMap version was keyed.
 */
public interface DocumentRepository extends JpaRepository<StoredDocument, String> {
}
