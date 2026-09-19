package com.mfstechnologies.mymobi.testsupport;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.data.jpa.repository.JpaRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * WORKSTREAM C (persistence): a small, reusable helper for tests that
 * need one of the Postgres-backed stores (RegisteredUserStore,
 * LoanStore, DocumentStore, ...) to behave like a real, working
 * in-memory collaborator - the same way the old ConcurrentHashMap-backed
 * versions did - rather than a Mockito mock that returns null/no-ops for
 * everything.
 *
 * Wires just the 4 JpaRepository methods each store's internals actually
 * call (findById, save, existsById, deleteById) to a real backing
 * HashMap. Every other method on JpaRepository (there are dozens -
 * findAll, saveAll, count, paging/sorting variants, etc.) is left
 * unstubbed entirely: Mockito's default null/no-op behavior for
 * unstubbed mock methods is exactly correct here, since nothing in this
 * codebase calls them - there's no need to implement the rest of the
 * interface just to satisfy it.
 *
 * All 4 stubs use lenient(): most individual test methods only exercise
 * one or two of the four operations (e.g. a test that only reads never
 * touches save/exists/delete), and Mockito's strict-stubs mode
 * (MockitoExtension's default) treats any stub a given test never
 * actually invokes as an UnnecessaryStubbingException. lenient() is the
 * documented, intended way to opt a shared setup helper like this one
 * out of that check, since "not every test uses every stub" is expected
 * and correct here, not a mistake to be flagged.
 *
 * Usage in a test:
 *   @Mock
 *   private RegisteredUserRepository repository;
 *
 *   @BeforeEach
 *   void setUp() {
 *       FakeRepositories.wireAsInMemoryStore(repository, RegisteredUser::getPhoneNumber);
 *       userStore = new RegisteredUserStore(repository);
 *   }
 */
public final class FakeRepositories {

    private FakeRepositories() {
    }

    public static <T, ID> void wireAsInMemoryStore(JpaRepository<T, ID> mockRepository, Function<T, ID> idExtractor) {
        Map<ID, T> backing = new HashMap<>();

        lenient().when(mockRepository.findById(any())).thenAnswer(invocation -> {
            ID id = invocation.getArgument(0);
            return Optional.ofNullable(backing.get(id));
        });

        lenient().when(mockRepository.save(any())).thenAnswer(invocation -> {
            T entity = invocation.getArgument(0);
            backing.put(idExtractor.apply(entity), entity);
            return entity;
        });

        lenient().when(mockRepository.existsById(any())).thenAnswer(invocation -> {
            ID id = invocation.getArgument(0);
            return backing.containsKey(id);
        });

        lenient().doAnswer(invocation -> {
            ID id = invocation.getArgument(0);
            backing.remove(id);
            return null;
        }).when(mockRepository).deleteById(any());
    }
}
