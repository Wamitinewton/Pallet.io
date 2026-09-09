/**
 * Test-scope infrastructure shared by every Pallet service test suite: composed test-slice
 * annotations ({@link io.pallet.common.test.annotations}), singleton Testcontainers holders
 * ({@link io.pallet.common.test.containers}), and AssertJ assertions for the platform's HTTP
 * envelopes ({@link io.pallet.common.test.assertions}).
 *
 * <p>Never imported outside a {@code src/test} tree. A consuming service adds this module with
 * {@code <scope>test</scope>}, and Maven's scope transitivity confines everything it pulls in
 * (Testcontainers, the Boot test-slice starters) to test scope too.
 */
package io.pallet.common.test;
