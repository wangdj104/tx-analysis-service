/* Plain script: works when index.html is opened directly, without a server. */
(() => {
  const localDay = date => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  function createState(now = new Date()) {
    const patients = [
      { id: 1, name: 'Emma Carter', relation: 'Mother', age: 65, avatar: 'EC', records: [], tasks: [
        { id: 11, time: '08:00', title: 'Morning medication', detail: 'Demo Medication A · 1 tablet', type: 'medication', done: true },
        { id: 12, time: '14:00', title: 'Afternoon medication', detail: 'Demo Medication B · 1 tablet', type: 'medication', done: false },
        { id: 13, time: '19:00', title: 'Evening blood pressure', detail: 'Record the reading so the family can follow changes', type: 'vital', done: false },
        { id: 14, time: '20:00', title: 'Prepare for the follow-up visit', detail: 'Bring recent reports and questions for the clinician', type: 'care', done: false }
      ], stocks: [28, 8] },
      { id: 2, name: 'Daniel Carter', relation: 'Father', age: 68, avatar: 'DC', records: [], tasks: [
        { id: 21, time: '08:30', title: 'Morning medication', detail: 'Demo Medication A · 1 tablet', type: 'medication', done: false },
        { id: 22, time: '19:30', title: 'Evening blood pressure', detail: "Share today's reading with the family", type: 'vital', done: false }
      ], stocks: [18, 16] }
    ].map(person => ({ ...person, records: Array.from({ length: 30 }, (_, i) => {
      const date = new Date(now); date.setDate(date.getDate() - 29 + i);
      return { date: localDay(date), time: '08:00', systolic: 122 + (i * 7 + person.id * 3) % 15, diastolic: 73 + (i * 3 + person.id) % 10 };
    }) }));
    return { patientId: 1, patients };
  }
  function current(state) { return state.patients.find(patient => patient.id === state.patientId); }
  function complete(state, id) {
    const person = current(state), task = person.tasks.find(item => item.id === id);
    if (!task || task.done) return false;
    task.done = true;
    if (task.type === 'medication') {
      const index = task.detail.includes(' B ') ? 1 : 0;
      person.stocks[index] = Math.max(0, person.stocks[index] - 1);
    }
    return true;
  }
  function addVital(state, systolic, diastolic, now = new Date()) {
    if (!Number.isInteger(systolic) || !Number.isInteger(diastolic) || systolic < 50 || systolic > 260 || diastolic < 30 || diastolic > 160 || systolic <= diastolic) {
      throw new Error('Enter valid whole numbers. Systolic pressure must be higher than diastolic pressure.');
    }
    const person = current(state);
    person.records.push({ date: localDay(now), time: `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`, systolic, diastolic });
    person.tasks.filter(task => task.type === 'vital').forEach(task => { task.done = true; });
  }
  function csv(state) {
    return '\uFEFFDate,Time,Systolic (mmHg),Diastolic (mmHg),Data source\r\n' + current(state).records.map(record => `${record.date},${record.time},${record.systolic},${record.diastolic},Fictional demo data`).join('\r\n');
  }
  globalThis.HealthDemo = { createState, current, complete, addVital, csv };
})();
