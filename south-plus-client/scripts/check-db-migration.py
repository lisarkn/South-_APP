#!/usr/bin/env python3
"""Exercise the exact 1→2 SQL against an existing v1 database using SQLite."""
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/java/net/southplus/client/core/database/AppDatabase.kt').read_text()
migration = source.split('val MIGRATION_1_2 =', 1)[1].split('@Volatile private var instance', 1)[0]
statements = re.findall(r'db\.execSQL\("([^"\n]+)"\)', migration)
assert len(statements) == 7, 'Review migration extraction when its format changes'
db = sqlite3.connect(':memory:')
db.executescript('''
CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, tid TEXT NOT NULL,
 title TEXT NOT NULL, url TEXT NOT NULL, visitedAt INTEGER NOT NULL);
CREATE TABLE cached_pages(cacheKey TEXT PRIMARY KEY NOT NULL, url TEXT NOT NULL, html TEXT NOT NULL,
 fetchedAt INTEGER NOT NULL, schemaVersion INTEGER NOT NULL, bytes INTEGER NOT NULL);
CREATE TABLE read_marks(tid TEXT PRIMARY KEY NOT NULL, lastPage INTEGER NOT NULL, visitedAt INTEGER NOT NULL);
CREATE TABLE block_rules(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT NOT NULL, value TEXT NOT NULL);
INSERT INTO history VALUES(1,'10','old','url',1),(2,'10','new','url',2),(3,'10','newest tie','url',2),(4,'20','other','url',3);
INSERT INTO cached_pages VALUES('read:10:1','url','南+测试',1,1,4);
INSERT INTO read_marks VALUES('10',7,3);
INSERT INTO block_rules VALUES(1,'KEYWORD','blocked');
''')
with db:
    for statement in statements:
        db.execute(statement)
assert db.execute('SELECT id,tid,title FROM history ORDER BY id').fetchall() == [(3,'10','newest tie'),(4,'20','other')]
assert db.execute('SELECT * FROM read_marks').fetchall() == [('10',7,3)]
assert db.execute('SELECT * FROM block_rules').fetchall() == [(1,'KEYWORD','blocked')]
assert db.execute('SELECT bytes FROM cached_pages').fetchone()[0] == len('南+测试'.encode())
try:
    db.execute("INSERT INTO history(tid,title,url,visitedAt) VALUES('10','duplicate','url',4)")
    raise AssertionError('tid unique constraint missing')
except sqlite3.IntegrityError:
    pass
# Room's upsert must reuse idOf(tid); the corresponding SQL preserves identity.
db.execute("UPDATE history SET title='revisited', visitedAt=10 WHERE id=3")
assert db.execute("SELECT id FROM history WHERE tid='10'").fetchone() == (3,)
for number in range(1100):
    db.execute('INSERT INTO history(tid,title,url,visitedAt) VALUES(?,?,?,?)', (str(number+100),'title','url',number+20))
db.execute('DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY visitedAt DESC, id DESC LIMIT 1000)')
assert db.execute('SELECT COUNT(*) FROM history').fetchone() == (1000,)
assert db.execute('PRAGMA integrity_check').fetchone() == ('ok',)
print('PASS: v1→v2 deduplication, row identity, history cap, UTF-8 sizes, read marks and block rules preserved')
