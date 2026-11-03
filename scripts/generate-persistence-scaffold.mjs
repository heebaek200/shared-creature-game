import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const document = await readFile(path.join(root, 'docs', 'database-design.md'), 'utf8');
const base = path.join(root, 'src', 'main', 'java', 'com', 'team', 'sharedcreature');

const packageByTable = new Map();
for (const table of ['users', 'social_accounts', 'user_profiles', 'oauth_login_attempts']) packageByTable.set(table, 'auth');
for (const table of ['game_groups', 'group_memberships', 'group_invite_links', 'group_join_restrictions', 'user_reports', 'moderation_actions', 'group_admin_changes']) packageByTable.set(table, 'group');
for (const table of ['seasons', 'creature_species', 'group_seasons', 'group_creatures', 'creature_experience_ledgers', 'season_member_contributions', 'season_group_scores', 'season_member_rank_snapshots', 'season_group_rank_snapshots']) packageByTable.set(table, 'season');
for (const table of ['quest_templates', 'quest_template_versions', 'quest_periods', 'quest_assignments', 'quest_completions', 'quest_reward_ledgers', 'user_activity_events', 'service_check_ins', 'photo_submissions']) packageByTable.set(table, 'quest');
for (const table of ['shop_items', 'owned_items', 'avatar_equipments']) packageByTable.set(table, 'avatar');
for (const table of ['purchases', 'payment_transactions', 'refund_requests', 'pg_api_attempts', 'payment_webhook_events']) packageByTable.set(table, 'payment');
for (const table of ['scheduler_executions', 'admin_audit_logs', 'api_idempotency_records']) packageByTable.set(table, 'admin');

function pascal(value) {
  return value.split('_').map(part => part[0].toUpperCase() + part.slice(1)).join('');
}

function entityName(table) {
  if (table === 'game_groups') return 'Group';
  if (table === 'creature_species') return 'CreatureSpecies';
  return pascal(table.endsWith('ies') ? `${table.slice(0, -3)}y` : table.endsWith('s') ? table.slice(0, -1) : table);
}

function camel(value) {
  const name = pascal(value);
  return name[0].toLowerCase() + name.slice(1);
}

function javaType(sqlType) {
  if (/BIGINT/i.test(sqlType)) return 'Long';
  if (/SMALLINT|INT/i.test(sqlType)) return 'Integer';
  if (/DECIMAL/i.test(sqlType)) return 'BigDecimal';
  if (/BOOLEAN/i.test(sqlType)) return 'Boolean';
  if (/DATETIME|TIMESTAMP/i.test(sqlType)) return 'Instant';
  if (/\bDATE\b/i.test(sqlType)) return 'LocalDate';
  if (/\bTIME\b/i.test(sqlType)) return 'LocalTime';
  return 'String';
}

function columnOptions(sqlType, description) {
  const options = [];
  const length = sqlType.match(/(?:VARCHAR|CHAR)\((\d+)\)/i)?.[1];
  if (length) options.push(`length = ${length}`);
  if (/UNIQUE/i.test(description)) options.push('unique = true');
  if (/선택|NULL 가능|없으면 NULL|전에는 NULL|미접속 강퇴는 NULL/i.test(description)) options.push('nullable = true');
  else options.push('nullable = false');
  return options;
}

const heading = /^(?:####|### \d+\.\d+\.) `([^`]+)`$/gm;
const matches = [...document.matchAll(heading)];
const tables = [];

for (let index = 0; index < matches.length; index += 1) {
  const table = matches[index][1];
  if (!packageByTable.has(table)) continue;
  const start = matches[index].index + matches[index][0].length;
  const end = matches[index + 1]?.index ?? document.length;
  const section = document.slice(start, end);
  const rows = [...section.matchAll(/^\| `([^`]+)` \| ([^|]+) \| ([^|]+) \|$/gm)]
    .map(row => ({ name: row[1].trim(), sqlType: row[2].trim(), description: row[3].trim() }));
  if (rows.length === 0) throw new Error(`No columns found for ${table}`);
  tables.push({ table, rows });
}

if (tables.length !== 40) throw new Error(`Expected 40 tables, found ${tables.length}`);

for (const { table, rows } of tables) {
  const feature = packageByTable.get(table);
  const className = entityName(table);
  const entityDir = path.join(base, feature, 'domain');
  const repositoryDir = path.join(base, feature, 'infrastructure');
  await mkdir(entityDir, { recursive: true });
  await mkdir(repositoryDir, { recursive: true });

  const imports = new Set([
    'jakarta.persistence.Column',
    'jakarta.persistence.Entity',
    'jakarta.persistence.FetchType',
    'jakarta.persistence.GeneratedValue',
    'jakarta.persistence.GenerationType',
    'jakarta.persistence.Id',
    'jakarta.persistence.JoinColumn',
    'jakarta.persistence.ManyToOne',
    'jakarta.persistence.Table',
    'jakarta.persistence.Version',
    'lombok.AccessLevel',
    'lombok.Getter',
    'lombok.NoArgsConstructor',
  ]);
  const fields = [];

  for (const row of rows) {
    if (row.name === 'id') {
      fields.push('    @Id\n    @GeneratedValue(strategy = GenerationType.IDENTITY)\n    private Long id;');
      continue;
    }

    const fk = row.description.match(/FK\s*→\s*`([^`.]+)\.id`/i);
    if (fk && packageByTable.has(fk[1])) {
      const targetTable = fk[1];
      const targetFeature = packageByTable.get(targetTable);
      const targetClass = entityName(targetTable);
      if (targetFeature !== feature) imports.add(`com.team.sharedcreature.${targetFeature}.domain.${targetClass}`);
      const fieldName = camel(row.name.replace(/_id$/, ''));
      const nullable = /선택|NULL|없으면/i.test(row.description) ? 'true' : 'false';
      fields.push(`    @ManyToOne(fetch = FetchType.LAZY)\n    @JoinColumn(name = "${row.name}", nullable = ${nullable})\n    private ${targetClass} ${fieldName};`);
      continue;
    }

    const type = javaType(row.sqlType);
    if (type === 'BigDecimal') imports.add('java.math.BigDecimal');
    if (type === 'Instant') imports.add('java.time.Instant');
    if (type === 'LocalDate') imports.add('java.time.LocalDate');
    if (type === 'LocalTime') imports.add('java.time.LocalTime');

    if (row.name === 'version') {
      fields.push('    @Version\n    @Column(name = "version", nullable = false)\n    private Long version;');
      continue;
    }

    const generated = /STORED 생성 컬럼/i.test(row.description);
    const options = columnOptions(row.sqlType, row.description);
    if (generated) options.push('insertable = false', 'updatable = false');
    const fieldName = camel(row.name);
    fields.push(`    @Column(name = "${row.name}", ${options.join(', ')})\n    private ${type} ${fieldName};`);
  }

  const importText = [...imports].sort().map(value => `import ${value};`).join('\n');
  const entity = `package com.team.sharedcreature.${feature}.domain;\n\n${importText}\n\n/**\n * ${table} 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.\n */\n@Getter\n@Entity\n@Table(name = "${table}")\n@NoArgsConstructor(access = AccessLevel.PROTECTED)\npublic class ${className} {\n\n${fields.join('\n\n')}\n}\n`;
  await writeFile(path.join(entityDir, `${className}.java`), entity);

  const repository = `package com.team.sharedcreature.${feature}.infrastructure;\n\nimport com.team.sharedcreature.${feature}.domain.${className};\nimport org.springframework.data.jpa.repository.JpaRepository;\n\n/**\n * ${className} 엔티티의 기본 영속성 연산을 제공합니다.\n */\npublic interface ${className}Repository extends JpaRepository<${className}, Long> {\n}\n`;
  await writeFile(path.join(repositoryDir, `${className}Repository.java`), repository);
}

console.log(`Generated ${tables.length} entities and repositories.`);
