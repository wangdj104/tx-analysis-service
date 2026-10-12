# Frozen doctor-plan density reference

`en.vue` and `cn.vue` are exact, unmodified copies of
`frontend/src/components/care-plan/PlanTaskList.vue` and
`cn/frontend/src/components/care-plan/PlanTaskList.vue` from commit
`c8756525dd7f7892dc4a836ac22d677af764c132`.

The care browser acceptance harness compiles these original SFCs, renders them
on an isolated `/baseline` fixture route, and feeds them the same synthetic API
plan/actions used for the current production component. The test host inherits
its width and root font properties from the current measured `.plan-tasks`.
The baseline is never injected into the production view. The sole compile-time
rewrite fixes the original relative ReceiptDialog import so the frozen files
can reside here; that dialog is not opened for baseline measurement.

Reports record each frozen file's SHA-256, the source commit, current and old
`.plan-card` and `.plan-tasks` heights, individual open-task heights, actual
container widths, and a baseline screenshot for every edition/viewport/theme.
There are no hard-coded or estimated historical pixel heights. A 30% minimum
reduction is asserted for the entire `.plan-card` with the same closed history
and open actions. Browser acceptance can only pass after those live DOM
measurements and the unchanged-action/history assertions succeed.
