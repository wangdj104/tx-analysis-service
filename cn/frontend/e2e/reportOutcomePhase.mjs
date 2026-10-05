import {test} from '@playwright/test'
// Fixed labels only. The reporter never copies arbitrary step titles or payloads.
export const reportPhase=(phase,operation=async()=>{})=>test.step(`care-phase:${phase}`,operation)
