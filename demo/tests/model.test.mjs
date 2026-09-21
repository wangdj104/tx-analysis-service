import test from 'node:test';
import assert from 'node:assert/strict';
import '../model.js';

const { createState, current, complete, addVital, csv } = globalThis.HealthDemo;

test('task completion deducts once and never affects another family member', () => {
  const state = createState();
  assert.equal(complete(state, 12), true);
  assert.equal(current(state).stocks[1], 7);
  assert.equal(complete(state, 12), false);
  assert.equal(current(state).stocks[1], 7);
  state.patientId = 2;
  assert.equal(current(state).stocks[1], 16);
  assert.equal(complete(state, 12), false);
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
  assert.deepEqual(current(state).records.at(-1), { date: '2026-09-17', time: '00:15', systolic: 126, diastolic: 78 });
  assert.equal(current(state).tasks.find(t => t.type === 'vital').done, true);
  state.patientId = 1;
  assert.equal(current(state).records.length, 30);
  assert.equal(current(state).tasks.find(t => t.type === 'vital').done, false);
});

test('reset creates fresh data; export contains selected records and explicit demo labels', () => {
  const state = createState();
  addVital(state, 126, 78);
  const output = csv(state);
  assert.ok(output.startsWith('\uFEFF日期'));
  assert.equal(output.split('\r\n').length, 32);
  assert.ok(output.includes('126,78,虚构演示数据'));
  assert.equal(current(createState()).records.length, 30);
  assert.equal(current(createState()).stocks[1], 8);
});
