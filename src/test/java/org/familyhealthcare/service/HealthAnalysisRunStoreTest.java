package org.familyhealthcare.service;
import org.junit.jupiter.api.Test;
class HealthAnalysisRunStoreTest {
 @Test void durableClaimsRecoveryAtomicDraftsAndConfigurationFences() throws Exception {try(CarePlanTestFixture f=new CarePlanTestFixture()){HealthAnalysisRunAssertions.verify(f.jdbc());}}
}
