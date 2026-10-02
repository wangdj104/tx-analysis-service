import assert from 'node:assert/strict';
import test from 'node:test';
import { demo } from './helpers/runtime.mjs';

for (const edition of ['en', 'zh']) {
  test(`${edition}: pending-review count uses the correct unit for zero, one and many`, () => {
    const app = demo(edition);
    for (const count of [0, 1, 2]) {
      app.state.reviews.forEach((review, index) => { review.status = index < count ? 'pending' : 'approved'; });
      const unit = edition === 'zh' ? '项' : count === 1 ? 'item' : 'items';
      assert.match(app.view.stats(), new RegExp(`<strong>${count}<small> ${unit}</small>`));
    }
  });
}
