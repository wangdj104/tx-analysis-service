import assert from 'node:assert/strict';
import test from 'node:test';
import { demo } from './helpers/runtime.mjs';

for (const edition of ['en', 'zh']) {
  test(`${edition}: patient summaries never fall back to another patient's appointment or note`, () => {
    const app = demo(edition), language = edition === 'en' ? 'en' : 'zh';
    const appointment = app.state.appointments[0], note = app.state.notes[0];
    app.state.patientId = 2;
    for (const renderer of [app.view.stats, app.view.appointments]) {
      const html = renderer();
      assert.ok(!html.includes(appointment.clinic[language]));
      assert.match(html, edition === 'en' ? /No upcoming appointment/ : /暂无复诊安排/);
    }
    assert.ok(!app.view.doctorOverview().includes(note.text[language]));
    assert.match(app.view.doctorOverview(), edition === 'en' ? /No clinical note/ : /暂无医生笔记/);
    app.state.appointments = []; app.state.notes = [];
    assert.doesNotThrow(() => app.view.doctorOverview());
    assert.doesNotThrow(() => app.view.appointments());
  });
  test(`${edition}: appointment and note selection follows patient and date order`, () => {
    const app = demo(edition);
    app.state.patientId = 2;
    app.state.appointments.push(
      { id: 2, patientId: 2, date: '2099-12-02', time: '10:00', clinic: { en: 'Later B', zh: 'Later B' } },
      { id: 3, patientId: 2, date: '2099-12-01', time: '11:00', clinic: { en: 'Sooner B', zh: 'Sooner B' } }
    );
    app.state.notes.push({ id: 2, patientId: 2, date: '2099-12-01', author: { en: 'Doctor B', zh: 'Doctor B' }, text: { en: 'Note B', zh: 'Note B' } });
    assert.ok(app.view.stats().includes('Sooner B'));
    const html = app.view.appointments();
    assert.ok(html.indexOf('Sooner B') < html.indexOf('Later B'));
    assert.ok(app.view.doctorOverview().includes('Note B'));
  });
}

for (const edition of ['en', 'zh']) {
  test(`${edition}: patient review counts and upcoming visits exclude unrelated or expired entries`, () => {
    const app=demo(edition);
    app.state.role='patient'; app.state.patientId=3;
    app.state.appointments.push({id:999,patientId:3,date:'2000-01-01',time:'09:00',clinic:{en:'Expired visit',zh:'Expired visit'}});
    const html=app.view.stats();
    assert.ok(!html.includes('Expired visit'));
    assert.match(html,edition==='en'?/Pending clinical reviews<\/span><strong>0/:/待临床复核<\/span><strong>0/);
    assert.ok(!app.view.appointments().includes('Expired visit'));
  });
}
