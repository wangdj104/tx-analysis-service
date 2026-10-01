package org.familyhealthcare.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.familyhealthcare.controller.FamilyHealthController;
import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.impl.MedicationReminderServiceImpl;
import org.familyhealthcare.util.DataScopeHelper;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real mapper/SQL coverage from prescription activation and reminder edits through intake check-in. */
class MedicationStockWorkflowTest {
    private CareDatabaseWorkflowTest database;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private MedicationMapper medications;
    private MedicationReminderMapper reminders;
    private MedicationIntakeMapper intakes;
    private CareItemMapper items;
    private MedicationStockService stock;
    private MedicationReminderServiceImpl reminderService;
    private FamilyCareService care;
    private FamilyHealthController controller;

    @BeforeEach void setup() throws Exception {
        database = new CareDatabaseWorkflowTest();
        database.setup();
        jdbc = database.jdbc;
        transaction = new TransactionTemplate(new DataSourceTransactionManager(database.ds));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(MedicationMapper.class);
        configuration.addMapper(MedicationReminderMapper.class);
        configuration.addMapper(MedicationIntakeMapper.class);
        configuration.addMapper(CareItemMapper.class);
        configuration.addMapper(PatientMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(database.ds);
        factory.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        medications = session.getMapper(MedicationMapper.class);
        reminders = session.getMapper(MedicationReminderMapper.class);
        intakes = session.getMapper(MedicationIntakeMapper.class);
        items = session.getMapper(CareItemMapper.class);
        PatientMapper patients = session.getMapper(PatientMapper.class);
        DataScopeHelper scope = new DataScopeHelper();
        ReflectionTestUtils.setField(scope, "patientMapper", patients);
        ReflectionTestUtils.setField(scope, "jdbcTemplate", jdbc);
        CareMembershipService membership = new CareMembershipService();
        ReflectionTestUtils.setField(membership, "jdbc", jdbc);
        ReflectionTestUtils.setField(membership, "patients", patients);

        stock = new MedicationStockService();
        ReflectionTestUtils.setField(stock, "jdbc", jdbc);
        ReflectionTestUtils.setField(stock, "scope", scope);
        ReflectionTestUtils.setField(stock, "medications", medications);
        ReflectionTestUtils.setField(stock, "items", items);
        reminderService = new MedicationReminderServiceImpl();
        ReflectionTestUtils.setField(reminderService, "baseMapper", reminders);
        ReflectionTestUtils.setField(reminderService, "dataScopeHelper", scope);
        ReflectionTestUtils.setField(reminderService, "medicationMapper", medications);
        ReflectionTestUtils.setField(reminderService, "medicationIntakeMapper", intakes);
        care = new FamilyCareService();
        ReflectionTestUtils.setField(care, "items", items);
        ReflectionTestUtils.setField(care, "scope", scope);
        ReflectionTestUtils.setField(care, "membership", membership);
        ReflectionTestUtils.setField(care, "medications", medications);
        ReflectionTestUtils.setField(care, "reminders", reminders);
        ReflectionTestUtils.setField(care, "jdbc", jdbc);
        controller = new FamilyHealthController();
        ReflectionTestUtils.setField(controller, "scope", scope);
        ReflectionTestUtils.setField(controller, "reminders", reminderService);
        ReflectionTestUtils.setField(controller, "intakeMapper", intakes);
        ReflectionTestUtils.setField(controller, "stockService", stock);
        ReflectionTestUtils.setField(controller, "medicationMapper", medications);
        transaction.execute(status -> {
            stock.configure(1L, 3L, new BigDecimal("20"), "tablet", 7, BigDecimal.ZERO);
            return null;
        });
    }

    @AfterEach void cleanup() { database.cleanup(); }

    @Test void editedPrescriptionReminderDeductsTheCurrentIntakeDose() {
        MedicationIntake intake = prescribe("1");
        MedicationReminder reminder = reminders.selectById(intake.getReminderId());
        reminder.setDosage("2 tablets");
        assertTrue(reminderService.saveOrUpdateReminder(reminder));

        assertEquals(200, take(intake.getId(), null));

        MedicationIntake saved = intakes.selectById(intake.getId());
        assertEquals("TAKEN", saved.getStatus());
        assertEquals("2 tablets", saved.getDosage());
        assertQuantity("18", remaining(3L));
        assertQuantity("2", recordedQuantity(intake.getId()));
        CareItem order = items.selectOne(new QueryWrapper<CareItem>().eq("kind", "ORDER"));
        Map<?, ?> originalDose = (Map<?, ?>) ((List<?>) order.getDetails().get("doses")).get(0);
        assertEquals("1", originalDose.get("quantity").toString(), "Prescription history must stay immutable");
    }

    @Test void changingMedicationUsesTheNewMedicationsDoseAndInventory() {
        MedicationIntake intake = prescribe("1");
        jdbc.update("INSERT INTO medication(id,patient_id,user_id,drug_name,is_active) VALUES(4,1,7,'Second medication',1)");
        stock.configure(1L, 4L, new BigDecimal("20"), "capsule", 7, BigDecimal.ZERO);
        MedicationReminder reminder = reminders.selectById(intake.getReminderId());
        reminder.setMedicationId(4L);
        reminder.setDosage("2 capsules");
        assertTrue(reminderService.saveOrUpdateReminder(reminder));

        assertEquals(200, take(intake.getId(), null));

        assertEquals(4L, intakes.selectById(intake.getId()).getMedicationId());
        assertQuantity("20", remaining(3L));
        assertQuantity("18", remaining(4L));
        assertQuantity("2", recordedQuantity(intake.getId()));
    }

    @Test void ambiguousEditedDoseDoesNotInferHistoricalQuantityButExplicitQuantityStillWorks() {
        MedicationIntake intake = prescribe("1");
        MedicationReminder reminder = reminders.selectById(intake.getReminderId());
        reminder.setDosage("10 mg");
        assertTrue(reminderService.saveOrUpdateReminder(reminder));
        reminderService.ensureDailyTasks(1L, LocalDate.now());

        assertEquals(200, take(intake.getId(), null));

        assertEquals("TAKEN", intakes.selectById(intake.getId()).getStatus());
        assertQuantity("20", remaining(3L));
        assertNull(recordedQuantity(intake.getId()));
        assertEquals(0, movementCount(intake.getId()));
        MedicationIntake next = prescribe("1");
        jdbc.update("UPDATE medication_reminder SET dosage='10 mg' WHERE id=?", next.getReminderId());
        assertEquals(200, take(next.getId(), new BigDecimal("2.5")));
        assertQuantity("17.5", remaining(3L));
        assertQuantity("2.5", recordedQuantity(next.getId()));
    }

    @Test void changedMedicationCannotInheritTheOldPrescriptionsUnit() {
        MedicationIntake intake = prescribe("1");
        jdbc.update("INSERT INTO medication(id,patient_id,user_id,drug_name,is_active) VALUES(4,1,7,'Liquid medication',1)");
        stock.configure(1L, 4L, new BigDecimal("20"), "mL", 7, BigDecimal.ZERO);
        MedicationReminder reminder = reminders.selectById(intake.getReminderId());
        reminder.setMedicationId(4L);
        assertTrue(reminderService.saveOrUpdateReminder(reminder));
        reminderService.ensureDailyTasks(1L, LocalDate.now());

        assertEquals(200, take(intake.getId(), null));

        assertEquals("TAKEN", intakes.selectById(intake.getId()).getStatus());
        assertQuantity("20", remaining(3L));
        assertQuantity("20", remaining(4L));
        assertNull(recordedQuantity(intake.getId()));
        assertEquals(0, movementCount(intake.getId()));
    }

    @Test void uneditedPrescriptionDeductsOnceAndKeepsCompletedIntakeHistory() {
        MedicationIntake intake = prescribe("1.5");
        assertEquals(200, take(intake.getId(), null));
        assertNotEquals(200, take(intake.getId(), null));
        assertQuantity("18.5", remaining(3L));
        assertEquals(1, movementCount(intake.getId()));
        assertEquals(1, actionCount(intake.getId()));

        MedicationIntake next = prescribe("3");
        assertNotEquals(intake.getReminderId(), next.getReminderId());
        assertEquals("1.5 tablet", intakes.selectById(intake.getId()).getDosage());
        assertEquals(200, take(next.getId(), null));
        assertQuantity("15.5", remaining(3L));
    }

    @Test void ordinaryReminderAcceptsEquivalentExplicitInventoryUnits() {
        MedicationReminder reminder = new MedicationReminder();
        reminder.setPatientId(1L);
        reminder.setMedicationId(3L);
        reminder.setRemindTime("08:00");
        reminder.setRepeatDays("1,2,3,4,5,6,7");
        reminder.setDosage("1.5 片");
        assertTrue(reminderService.saveOrUpdateReminder(reminder));
        reminderService.ensureDailyTasks(1L, LocalDate.now());
        MedicationIntake intake = intakeFor(reminder.getId());

        assertEquals(200, take(intake.getId(), null));

        assertQuantity("18.5", remaining(3L));
    }

    @Test void missingOrNonNumericDoseNeverFallsBackToHistoricalPrescriptionQuantity() {
        for (String dosage : Arrays.asList(null, "", "As prescribed", "2", "0 tablets")) {
            MedicationIntake intake = prescribe("1");
            jdbc.update("UPDATE medication_reminder SET dosage=? WHERE id=?", dosage, intake.getReminderId());
            reminderService.ensureDailyTasks(1L, LocalDate.now());
            assertEquals(200, take(intake.getId(), null), String.valueOf(dosage));
            assertEquals("TAKEN", intakes.selectById(intake.getId()).getStatus());
            assertQuantity("20", remaining(3L));
            assertNull(recordedQuantity(intake.getId()));
            assertEquals(0, movementCount(intake.getId()));
        }
    }

    @Test void untrackedMedicationDoesNotRequireAnInventoryQuantity() {
        MedicationIntake intake = prescribe("1");
        jdbc.update("DELETE FROM medication_stock WHERE medication_id=3");
        jdbc.update("UPDATE medication_reminder SET dosage='As prescribed' WHERE id=?", intake.getReminderId());

        assertEquals(200, take(intake.getId(), null));

        assertEquals("TAKEN", intakes.selectById(intake.getId()).getStatus());
        assertNull(recordedQuantity(intake.getId()));
        assertEquals(0, movementCount(intake.getId()));
    }

    @Test void skipAndSnoozeDoNotRequireOrDeductAnAmbiguousQuantity() {
        MedicationIntake intake = prescribe("1");
        jdbc.update("UPDATE medication_reminder SET dosage='As prescribed' WHERE id=?", intake.getReminderId());
        assertEquals(200, transaction.execute(status -> controller.intakeAction(intake.getId(), "SNOOZED", null, null, "SELF").getCode()).intValue());
        assertEquals(200, transaction.execute(status -> controller.intakeAction(intake.getId(), "SKIPPED", "Not taken", null, "SELF").getCode()).intValue());

        assertEquals("SKIPPED", intakes.selectById(intake.getId()).getStatus());
        assertQuantity("20", remaining(3L));
        assertEquals(0, movementCount(intake.getId()));
    }

    @Test void matchingMultiWordUnitsDeductTheCurrentDose() {
        stock.configure(1L, 3L, new BigDecimal("20"), "international units", 7, BigDecimal.ZERO);
        MedicationIntake intake = prescribe("2", "international units");
        assertEquals(200, take(intake.getId(), null));
        assertQuantity("18", remaining(3L));
        assertQuantity("2", recordedQuantity(intake.getId()));
    }

    private MedicationIntake prescribe(String quantity) {
        return prescribe(quantity, "tablet");
    }

    private MedicationIntake prescribe(String quantity, String unit) {
        CareItem order = new CareItem();
        order.setPatientId(1L);
        order.setKind("ORDER");
        order.setTitle("Confirmed prescription");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("confirmed", true);
        details.put("medicationId", 3L);
        details.put("startDate", LocalDate.now().toString());
        details.put("action", "CHANGE");
        details.put("unit", unit);
        details.put("repeatDays", "1,2,3,4,5,6,7");
        Map<String, Object> dose = new LinkedHashMap<>();
        dose.put("time", "08:00");
        dose.put("quantity", quantity);
        details.put("doses", Collections.singletonList(dose));
        order.setDetails(details);
        transaction.execute(status -> care.save(order));
        reminderService.ensureDailyTasks(1L, LocalDate.now());
        Long reminderId = Long.valueOf(((List<?>) order.getDetails().get("reminderIds")).get(0).toString());
        return intakeFor(reminderId);
    }

    private MedicationIntake intakeFor(Long reminderId) {
        return intakes.selectOne(new QueryWrapper<MedicationIntake>().eq("reminder_id", reminderId));
    }

    private int take(Long id, BigDecimal quantity) {
        return transaction.execute(status -> controller.intakeAction(id, "TAKEN", null, quantity, "SELF").getCode());
    }

    private BigDecimal remaining(Long medicationId) {
        return jdbc.queryForObject("SELECT quantity FROM medication_stock WHERE medication_id=?", BigDecimal.class, medicationId);
    }

    private BigDecimal recordedQuantity(Long intakeId) {
        return jdbc.queryForObject("SELECT quantity FROM care_intake_action WHERE intake_id=?", BigDecimal.class, intakeId);
    }

    private int actionCount(Long intakeId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM care_intake_action WHERE intake_id=?", Integer.class, intakeId);
    }

    private int movementCount(Long intakeId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM medication_stock_movement WHERE source_key=?", Integer.class, "INTAKE:" + intakeId);
    }

    private void assertQuantity(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "Expected quantity " + expected + " but was " + actual);
    }
}
