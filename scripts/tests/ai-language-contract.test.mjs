import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'

for (const prefix of ['', 'cn/']) {
  const source = path => fs.readFileSync(new URL(`../../${prefix}src/main/java/org/familyhealthcare/${path}`, import.meta.url), 'utf8')
  test(`${prefix || 'root/'} AI generation explicitly selects narrative language and preserves both parser contracts`, () => {
    const analysis = source('service/impl/AiAnalysisServiceImpl.java')
    assert.match(analysis, /Write all narrative[^\n]*English/)
    assert.match(analysis, /简体中文/)
    assert.match(analysis, /callDeepSeek\(prompt\.toString\(\), false\)/)
    assert.match(analysis, /json\.getString\(chineseKey\)/)
    assert.match(analysis, /json\.getString\(englishKey\)/)
  })
  test(`${prefix || 'root/'} OCR has a source-preservation contract and localized system diagnostics`, () => {
    const ocr = source('service/impl/AiOcrServiceImpl.java')
    assert.match(ocr, /Preserve original/)
    assert.match(ocr, /OCR is not configured/)
    assert.match(ocr, /尚未配置/)
    assert.doesNotMatch(ocr, /not configuration OCR secret|already recognitionto|AI BackcontentNonemethodparse/)
  })
  test(`${prefix || 'root/'} scheduled generation scopes and restores the owner's language`, () => {
    const automation = source('service/HealthAnalysisAutomationService.java')
    assert.match(automation, /languagePreference\.get\(row\.getUserId\(\)\)/)
    assert.match(automation, /LocaleContextHolder\.setLocaleContext\(previousLocale\)/)
    assert.match(automation, /finally\s*\{/)
  })
}
