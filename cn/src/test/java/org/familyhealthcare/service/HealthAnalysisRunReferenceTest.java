package org.familyhealthcare.service;
import org.junit.jupiter.api.Test;
class HealthAnalysisRunReferenceTest {
 @Test void normalAndCraftedArchivesRestoreIndependentDisabledCopies()throws Exception{try(CarePlanTestFixture f=new CarePlanTestFixture()){HealthAnalysisRunReferenceAssertions.restoreCopiesDetachRuntime(f.jdbc());}}
 @Test void crossOwnerPointerCannotClearTheOriginalUncertaintyBlock()throws Exception{try(CarePlanTestFixture f=new CarePlanTestFixture()){HealthAnalysisRunReferenceAssertions.forgedPointerCannotAcknowledgeOtherOwner(f.jdbc());}}
 @Test void mismatchedAndMissingPointersFailClosedInEveryRunStage()throws Exception{try(CarePlanTestFixture f=new CarePlanTestFixture()){HealthAnalysisRunReferenceAssertions.invalidPointersFailClosedAcrossStages(f.jdbc());}}
}
