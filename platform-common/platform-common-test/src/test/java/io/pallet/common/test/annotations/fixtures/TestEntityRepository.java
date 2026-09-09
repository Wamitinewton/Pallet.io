package io.pallet.common.test.annotations.fixtures;

import org.springframework.data.jpa.repository.JpaRepository;

/** Throwaway repository backing {@code TestEntity}, used only by this module's own tests. */
public interface TestEntityRepository extends JpaRepository<TestEntity, Long> {}
