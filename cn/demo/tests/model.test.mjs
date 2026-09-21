import test from 'node:test';
import assert from 'node:assert/strict';
import '../model.js';

const { createState, current, complete, addVital, review, addPlan, addHandover, runFeature, toggleUser, updateBranding, csv } = globalThis.HealthDemo;

test('task completion deducts once and never affects another family member', () => {
  const state = createState();
  assert.equal(complete(state, 2), true);
  assert.equal(current(state).stocks[1], 7);
  assert.equal(complete(state, 2), false);
  assert.equal(current(state).stocks[1], 7);
  state.patientId = 2;
  assert.equal(current(state).stocks[1], 16);
  assert.equal(complete(state, 2), false);
});
test('new measurement updates selected family only and invalid input changes nothing', () => {
  const state = createState();
  state.patientId = 2;
  assert.throws(() => addVital(state, 70, 80));
  assert.throws(() => addVital(state, NaN, 80));
  assert.throws(() => addVital(state, 126.5, 80));
  assert.equal(current(state).records.length, 30);
  addVital(state, 126, 78, new Date(2026, 8, 17, 0, 15));
  assert.equal(current(state).records.length, 31);
  assert.deepEqual(current(state).records.at(-1), { id: current(state).records.at(-1).id, date: '2026-09-17', time: '00:15', systolic: 126, diastolic: 78 });
  assert.equal(current(state).tasks.find(t => t.type === 'vital').done, true);
  state.patientId = 1;
  assert.equal(current(state).records.length, 30);
  assert.equal(current(state).tasks.find(t => t.type === 'vital').done, false);
});

test('clinical review can be decided once and care plans remain patient-scoped', () => {
  const state = createState(new Date(2026, 8, 21));
  assert.equal(review(state, 101, 'approved'), true);
  assert.equal(review(state, 101, 'rejected'), false);
  assert.equal(state.reviews.find(item => item.id === 101).status, 'approved');
  const count = state.plans.length;
  addPlan(state, 'Review symptoms', '2026-09-30');
  assert.equal(state.plans.length, count + 1);
  assert.equal(state.plans[0].patientId, 1);
});

test('family handover and localized exports are supported', () => {
  const state = createState();
  addHandover(state, 'Evening medication completed.');
  assert.equal(state.handovers[0].patientId, 1);
  assert.ok(csv(state, 'zh').startsWith('\uFEFF日期,时间'));
});

test('full-system demo actions update state and remain auditable', () => {
  const state = createState();
  assert.equal(runFeature(state, 'clinical-import'), true);
  assert.equal(runFeature(state, 'clinical-import'), true);
  assert.equal(state.featureRuns['clinical-import'], 2);
  assert.match(state.audit[0].action.en, /clinical-import/);
  assert.equal(toggleUser(state, 2), true);
  assert.equal(state.users.find(user => user.id === 2).active, false);
  assert.match(state.audit[0].action.en, /Disabled/);
  assert.equal(toggleUser(state, 999), false);
});

test('reset creates fresh data; export contains selected records and explicit demo labels', () => {
  const state = createState();
  addVital(state, 126, 78);
  const output = csv(state);
  assert.ok(output.startsWith('\uFEFFDate'));
  assert.equal(output.split('\r\n').length, 32);
  assert.ok(output.includes('126,78,Fictional demo data'));
  assert.equal(current(createState()).records.length, 30);
  assert.equal(current(createState()).stocks[1], 8);
});

test('platform branding validates and updates every configurable identity field', () => {
  const state = createState();
  assert.equal(updateBranding(state, { platformName: 'Morning Star Health', organizationName: 'Demo Hospital', logo: 'assets/logo.svg', pageBackground: '#eef6f2', ownershipText: '© Demo Hospital' }), true);
  assert.equal(state.branding.platformName.en, 'Morning Star Health');
  assert.equal(state.branding.organizationName.zh, 'Demo Hospital');
  assert.equal(state.branding.pageBackground, '#eef6f2');
  assert.throws(() => updateBranding(state, { platformName: '', logo: 'javascript:alert(1)', pageBackground: 'red', ownershipText: '' }), /invalid-branding/);
});
