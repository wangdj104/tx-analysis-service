/* Plain script: works when index.html is opened directly, without a server. */
(() => {
  const localDay = date => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  function createState(now = new Date()) {
    const patients = [
      { id: 1, name: '张爱华', relation: '母亲', age: 65, avatar: '张', records: [], tasks: [
        { id: 11, time: '08:00', title: '晨间用药', detail: '演示药品 A · 1 片', type: 'medication', done: true },
        { id: 12, time: '14:00', title: '午后用药', detail: '演示药品 B · 1 片', type: 'medication', done: false },
        { id: 13, time: '19:00', title: '晚间血压', detail: '记录读数，方便家人持续关注变化', type: 'vital', done: false },
        { id: 14, time: '20:00', title: '准备复诊材料', detail: '携带近期报告和准备咨询医生的问题', type: 'care', done: false }
      ], stocks: [28, 8] },
      { id: 2, name: '李建国', relation: '父亲', age: 68, avatar: '李', records: [], tasks: [
        { id: 21, time: '08:30', title: '晨间用药', detail: '演示药品 A · 1 片', type: 'medication', done: false },
        { id: 22, time: '19:30', title: '晚间血压', detail: '与家人共享今天的读数', type: 'vital', done: false }
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
      throw new Error('请输入有效整数，且收缩压必须高于舒张压。');
    }
    const person = current(state);
    person.records.push({ date: localDay(now), time: `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`, systolic, diastolic });
    person.tasks.filter(task => task.type === 'vital').forEach(task => { task.done = true; });
  }
  function csv(state) {
    return '\uFEFF日期,时间,收缩压 (mmHg),舒张压 (mmHg),数据来源\r\n' + current(state).records.map(record => `${record.date},${record.time},${record.systolic},${record.diastolic},虚构演示数据`).join('\r\n');
  }
  globalThis.HealthDemo = { createState, current, complete, addVital, csv };
})();
