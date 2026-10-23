#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createReadStream, createWriteStream, constants } from 'node:fs';
import * as fs from 'node:fs/promises';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';
import { pathToFileURL } from 'node:url';

const sha = value => createHash('sha256').update(value).digest('hex');
const ident = value => {
  if (!/^[A-Za-z0-9_]+$/.test(value)) throw new Error('Database/table identifier must contain letters, numbers and underscores only.');
  return '`' + value + '`';
};
export function inside(root, child) {
  const relative = path.relative(path.resolve(root), path.resolve(child));
  return relative === '' || (!relative.startsWith('..' + path.sep) && relative !== '..' && !path.isAbsolute(relative));
}
export async function hashFile(file) {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(file)) hash.update(chunk);
  return hash.digest('hex');
}
export async function inventoryDirectory(root) {
  const result = [];
  async function visit(directory) {
    for (const entry of (await fs.readdir(directory, { withFileTypes: true })).sort((a,b) => a.name.localeCompare(b.name))) {
      const file = path.join(directory, entry.name);
      if (entry.isSymbolicLink()) throw new Error('Attachment trees must not contain symbolic links.');
      if (entry.isDirectory()) await visit(file);
      else if (entry.isFile()) {
        const stat = await fs.stat(file);
        result.push({ path: path.relative(root, file).split(path.sep).join('/'), size: stat.size, sha256: await hashFile(file) });
      } else throw new Error('Unsupported attachment filesystem entry.');
    }
  }
  await visit(root);
  return result.sort((a,b) => a.path.localeCompare(b.path));
}
export function assertSame(expected, actual, label) {
  if (JSON.stringify(expected) !== JSON.stringify(actual)) throw new Error(label + ' verification failed; the isolated copy remains quarantined.');
}
export function assertIsolated(source, target) {
  if (!source.uuid || !target.uuid || source.uuid === target.uuid) throw new Error('Restore requires a separate MySQL server with a different server_uuid.');
  if (target.eventScheduler.toUpperCase() === 'ON') throw new Error('The target event_scheduler must be OFF or DISABLED.');
}
function client(binary, args, { input, output, digest = false } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(binary, args, { windowsHide: true, stdio: ['pipe','pipe','pipe'] });
    const chunks = [], hash = createHash('sha256');
    let size = 0, ioError = null;
    // Do not print SQL/client diagnostics: they can contain clinical values or credential paths.
    child.stderr.on('data', () => {});
    child.on('error', reject);
    const writing = output ? pipeline(child.stdout, createWriteStream(output, { flags: 'wx', mode: 0o600 })).catch(error => { ioError = error; child.kill(); }) : null;
    if (!output) child.stdout.on('data', chunk => {
      if (digest) hash.update(chunk);
      else {
        size += chunk.length;
        if (size > 32 * 1024 * 1024) { ioError = new Error('Metadata result exceeds the bounded buffer.'); child.kill(); }
        else chunks.push(chunk);
      }
    });
    const reading = input ? pipeline(createReadStream(input), child.stdin).catch(error => { ioError = error; child.kill(); }) : null;
    if (!input) child.stdin.end();
    child.on('close', async code => {
      await Promise.all([writing, reading].filter(Boolean));
      if (ioError) return reject(ioError);
      if (code !== 0) return reject(new Error(path.basename(binary) + ' failed with exit code ' + code + '; no successful recovery is confirmed.'));
      resolve(digest ? hash.digest('hex') : Buffer.concat(chunks).toString('utf8').replace(/\r\n/g, '\n').trimEnd());
    });
  });
}
function databaseClient(options, defaults) {
  const base = ['--defaults-extra-file=' + path.normalize(defaults), '--protocol=tcp', '--default-character-set=utf8mb4', '--batch', '--raw', '--skip-column-names'];
  return (sql, database = 'information_schema', digest = false) => client(options.mysql, [...base, '--database=' + database, '--execute=' + sql], { digest });
}
async function server(query) {
  const [uuid, eventScheduler, version] = (await query('SELECT @@server_uuid,@@event_scheduler,VERSION()')).split('\t');
  return { uuid, eventScheduler, version };
}
async function snapshot(query, database) {
  const names = (await query("SELECT TABLE_NAME,TABLE_TYPE FROM information_schema.tables WHERE TABLE_SCHEMA='" + database + "' ORDER BY TABLE_NAME")).split('\n').filter(Boolean).map(row => row.split('\t'));
  const tables = {};
  for (const [name, type] of names) {
    if (name === 'restore_quarantine') continue;
    const table = ident(name);
    const schema = await query('SHOW CREATE TABLE ' + table, database);
    const entry = { type, schemaSha256: sha(schema) };
    if (type === 'BASE TABLE') {
      const columns = (await query("SELECT COLUMN_NAME FROM information_schema.columns WHERE TABLE_SCHEMA='" + database + "' AND TABLE_NAME='" + name + "' ORDER BY ORDINAL_POSITION")).split('\n').filter(Boolean);
      const row = columns.map(column => "IFNULL(SHA2(CAST(" + ident(column) + " AS BINARY),256),'NULL')").join(',');
      entry.rows = Number(await query('SELECT COUNT(*) FROM ' + table, database));
      entry.rowSha256 = await query("SELECT SHA2(CONCAT_WS('|'," + row + "),256) FROM " + table + ' ORDER BY 1', database, true);
    }
    tables[name] = entry;
  }
  const definitions = {};
  const contracts = {
    triggers: ['TRIGGERS','TRIGGER_NAME,EVENT_MANIPULATION,EVENT_OBJECT_TABLE,ACTION_ORDER,ACTION_CONDITION,ACTION_STATEMENT,ACTION_ORIENTATION,ACTION_TIMING,SQL_MODE,DEFINER,CHARACTER_SET_CLIENT,COLLATION_CONNECTION,DATABASE_COLLATION','TRIGGER_SCHEMA','TRIGGER_NAME'],
    routines: ['ROUTINES','ROUTINE_NAME,ROUTINE_TYPE,DATA_TYPE,DTD_IDENTIFIER,ROUTINE_DEFINITION,IS_DETERMINISTIC,SQL_DATA_ACCESS,SECURITY_TYPE,SQL_MODE,ROUTINE_COMMENT,DEFINER,CHARACTER_SET_CLIENT,COLLATION_CONNECTION,DATABASE_COLLATION','ROUTINE_SCHEMA','ROUTINE_NAME'],
    parameters: ['PARAMETERS','SPECIFIC_NAME,ORDINAL_POSITION,PARAMETER_MODE,PARAMETER_NAME,DATA_TYPE,DTD_IDENTIFIER','SPECIFIC_SCHEMA','SPECIFIC_NAME,ORDINAL_POSITION'],
    events: ['EVENTS','EVENT_NAME,DEFINER,TIME_ZONE,EVENT_DEFINITION,EVENT_TYPE,EXECUTE_AT,INTERVAL_VALUE,INTERVAL_FIELD,STARTS,ENDS,STATUS,ON_COMPLETION,SQL_MODE,EVENT_COMMENT,CHARACTER_SET_CLIENT,COLLATION_CONNECTION,DATABASE_COLLATION','EVENT_SCHEMA','EVENT_NAME']
  };
  for (const [kind,[table,columns,schema,order]] of Object.entries(contracts))
    definitions[kind] = sha(await query("SELECT " + columns + " FROM information_schema." + table + " WHERE " + schema + "='" + database + "' ORDER BY " + order));
  const attachments = tables.medical_record_attachment ? {
    rows: tables.medical_record_attachment.rows,
    contentSha256: await query("SELECT SHA2(CONCAT_WS('|',HEX(id),HEX(record_id),IFNULL(SHA2(file_content,256),'NULL')),256) FROM medical_record_attachment ORDER BY id", database, true)
  } : { rows: 0, contentSha256: sha('') };
  return { tables, definitions, databaseAttachments: attachments };
}
async function verifyExternalReferences(query, database, root, inventory) {
  const exists = Number(await query("SELECT COUNT(*) FROM information_schema.tables WHERE TABLE_SCHEMA='" + database + "' AND TABLE_NAME='medical_record_attachment'"));
  if (!exists) return 0;
  const references = (await query("SELECT IFNULL(HEX(file_path),'NULL') FROM medical_record_attachment WHERE file_content IS NULL OR LENGTH(TRIM(file_content))=0 ORDER BY id", database)).split('\n').filter(Boolean);
  const files = new Set(inventory.map(item => item.path));
  for (const encoded of references) {
    if (!root || encoded === 'NULL') throw new Error('An attachment has no verified embedded content or external file.');
    const reference = Buffer.from(encoded, 'hex').toString('utf8');
    const file = path.resolve(root, reference);
    if (!inside(root, file) || !files.has(path.relative(root, file).split(path.sep).join('/')))
      throw new Error('An external attachment reference is outside the supplied tree or missing from it.');
  }
  return references.length;
}
export async function verifyFullRestore(options) {
  ident(options.database);
  if (!options.writersStopped) throw new Error('Stop application writers and schedulers, then supply --writers-stopped.');
  const source = databaseClient(options, options.sourceDefaults), target = databaseClient(options, options.targetDefaults);
  const sourceServer = await server(source), targetServer = await server(target);
  assertIsolated(sourceServer, targetServer);
  if (sourceServer.version !== targetServer.version) throw new Error('Use matching MySQL engine versions for the restore rehearsal.');
  if (Number(await target("SELECT COUNT(*) FROM information_schema.schemata WHERE SCHEMA_NAME='" + options.database + "'"))) throw new Error('The target database must not already exist.');
  if (Number(await source("SELECT COUNT(*) FROM information_schema.tables WHERE TABLE_SCHEMA='" + options.database + "' AND TABLE_NAME='restore_quarantine'"))) throw new Error('A quarantined restore cannot be used as an unreviewed source.');
  const output = path.resolve(options.output);
  const attachmentRoot = options.attachmentsDirectory ? await fs.realpath(options.attachmentsDirectory) : null;
  if (attachmentRoot && (inside(attachmentRoot, output) || inside(output, attachmentRoot))) throw new Error('The output and attachment source must be separate directories.');
  await fs.mkdir(output, { mode: 0o700 }); // Exclusive new directory; never overwrite or delete any backup.
  const receipt = { format: 'chengxin-isolated-restore-v1', createdAt: new Date().toISOString(), database: options.database, sourceServer, targetServer, status: 'INCOMPLETE', applicationStartupAllowed: false };
  const receiptFile = path.join(output, 'verification.json');
  try {
    const originalFiles = attachmentRoot ? await inventoryDirectory(attachmentRoot) : [];
    const baseline = await snapshot(source, options.database);
    if (!Object.keys(baseline.tables).length) throw new Error('The source database is empty or inaccessible.');
    const externalReferenceCount = await verifyExternalReferences(source, options.database, attachmentRoot, originalFiles);
    const dumpFile = path.join(output, 'database.sql');
    await client(options.mysqldump, ['--defaults-extra-file=' + path.normalize(options.sourceDefaults),'--protocol=tcp','--default-character-set=utf8mb4','--lock-all-tables','--hex-blob','--routines','--events','--triggers','--set-gtid-purged=OFF','--skip-comments',options.database], { output: dumpFile });
    assertSame(baseline, await snapshot(source, options.database), 'Source database stability');
    const [charset,collation] = (await source("SELECT DEFAULT_CHARACTER_SET_NAME,DEFAULT_COLLATION_NAME FROM information_schema.schemata WHERE SCHEMA_NAME='" + options.database + "'")).split('\t');
    await target('CREATE DATABASE ' + ident(options.database) + ' CHARACTER SET ' + ident(charset) + ' COLLATE ' + ident(collation));
    await target("CREATE TABLE restore_quarantine (id INT PRIMARY KEY,active TINYINT NOT NULL,reason VARCHAR(255) NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP); INSERT INTO restore_quarantine(id,active,reason) VALUES(1,1,'Independent restore copy: authorization and notification replay are blocked')", options.database);
    await client(options.mysql, ['--defaults-extra-file=' + path.normalize(options.targetDefaults),'--protocol=tcp','--default-character-set=utf8mb4','--database=' + options.database], { input: dumpFile });
    if (Number(await target('SELECT COUNT(*) FROM restore_quarantine WHERE active=1', options.database)) !== 1) throw new Error('Restore quarantine marker is missing.');
    const restored = await snapshot(target, options.database);
    assertSame(baseline, restored, 'Database tables, rows, definitions and embedded attachments');
    const copies = path.join(output, 'attachments');
    await fs.mkdir(copies, { mode: 0o700 });
    for (const item of originalFiles) {
      const file = path.join(copies, ...item.path.split('/'));
      await fs.mkdir(path.dirname(file), { recursive: true, mode: 0o700 });
      await fs.copyFile(path.join(attachmentRoot, ...item.path.split('/')), file, constants.COPYFILE_EXCL);
    }
    assertSame(originalFiles, await inventoryDirectory(copies), 'External attachment copy');
    if (attachmentRoot) assertSame(originalFiles, await inventoryDirectory(attachmentRoot), 'Source attachment stability');
    receipt.status = 'VERIFIED_QUARANTINED';
    receipt.databaseDumpSha256 = await hashFile(dumpFile);
    receipt.database = { name: options.database, ...restored };
    receipt.externalAttachments = { referenced: externalReferenceCount, files: originalFiles };
    receipt.quarantine = { active: true, authorizationRestoredForUse: false, reminderReplayAllowed: false, runtimeGuard: 'RestoreQuarantineGuard' };
    await fs.writeFile(receiptFile, JSON.stringify(receipt, null, 2) + '\n', { mode: 0o600 });
    return receipt;
  } catch (error) {
    receipt.status = 'FAILED';
    receipt.error = error.message;
    await fs.writeFile(receiptFile, JSON.stringify(receipt, null, 2) + '\n', { mode: 0o600 });
    throw error;
  }
}
export function parseArgs(args) {
  const options = { mysql: 'mysql', mysqldump: 'mysqldump' };
  const keys = { '--source-defaults':'sourceDefaults', '--target-defaults':'targetDefaults', '--database':'database', '--output':'output', '--attachments-directory':'attachmentsDirectory', '--mysql':'mysql', '--mysqldump':'mysqldump' };
  for (let i=0;i<args.length;i++) {
    if (args[i] === '--writers-stopped') { options.writersStopped = true; continue; }
    const key = keys[args[i]];
    if (!key || !args[i+1] || args[i+1].startsWith('--')) throw new Error('Unknown or incomplete restore option.');
    options[key] = args[++i];
  }
  if (['sourceDefaults','targetDefaults','database','output'].some(key => !options[key])) throw new Error('Required: --source-defaults FILE --target-defaults FILE --database NAME --output NEW_DIRECTORY --writers-stopped');
  return options;
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href)
  verifyFullRestore(parseArgs(process.argv.slice(2))).then(() => console.log('VERIFIED_QUARANTINED: database and attachment checks passed; application startup remains blocked.')).catch(error => { console.error(error.message); process.exitCode = 1; });
