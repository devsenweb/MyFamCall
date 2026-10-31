import { after, before, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { get, ref, set, update, runTransaction } from 'firebase/database';

let env;
const family = 'families/private-family';
const path = `${family}/call`;
const id1 = '11111111-1111-4111-a111-111111111111';
const id2 = '22222222-2222-4222-a222-222222222222';
const db = uid => uid ? env.authenticatedContext(uid).database() : env.unauthenticatedContext().database();
const at = (uid, child = '') => ref(db(uid), path + (child ? '/' + child : ''));
function call(id = id1, caller = 'senthil', callee = 'shalini', options = {}) {
  const now = Date.now();
  return { id, caller, callee, createdAt: now, expiresAt: now + 7_190_000,
    video: true, status: { callId: id, value: 'ringing' }, ...options };
}
const status = (id, value) => ({callId:id,value});
const sdp = (id = id1, revision = 1) => ({callId:id,revision,sdp:'v=0\r\na=fingerprint:sha-256 test\r\n'});
const candidate = (id = id1, revision = 1) => ({callId:id,revision,mid:'0',line:0,sdp:'candidate:1 1 udp 2122260223 192.0.2.1 12345 typ host'});
async function seedCall(c) { await env.withSecurityRulesDisabled(ctx => set(ref(ctx.database(),path),c)); }
async function accepted() { await assertSucceeds(set(at('senthil'),call())); await assertSucceeds(set(at('shalini','status'),status(id1,'accepted'))); }

before(async () => {
  env = await initializeTestEnvironment({ projectId:'demo-my-fam-call',
    database:{host:'127.0.0.1',port:9000,rules:fs.readFileSync(new URL('../database.rules.json',import.meta.url),'utf8')} });
});
beforeEach(async () => {
  await env.clearDatabase();
  await env.withSecurityRulesDisabled(ctx => set(ref(ctx.database(),family),{ownerUid:'senthil',partnerUid:'shalini',members:{senthil:true,shalini:true}}));
});
after(async () => { if (env) await env.cleanup(); });

test('unauthenticated and unapproved identities cannot read, write, list, or become members',async()=>{
  for (const uid of [null,'outsider']) {
    await assertFails(get(ref(db(uid),family+'/members')));
    await assertFails(get(at(uid)));
    await assertFails(set(at(uid),call()));
    await assertFails(set(ref(db(uid),family+'/members/outsider'),true));
  }
  await assertFails(get(ref(db('senthil'),'families')));
  await assertFails(get(ref(db('senthil'),family)));
  await assertSucceeds(get(ref(db('senthil'),family+'/members')));
  await assertFails(set(ref(db('senthil'),family+'/members/outsider'),true));
});

test('approved identities can signal without email claims; unapproved authenticated users cannot',async()=>{
  for (const claim of [{email_verified:false}, {}]) {
    const callerDb=env.authenticatedContext('senthil',claim).database();
    const calleeDb=env.authenticatedContext('shalini',claim).database();
    await seedCall(null);
    await assertSucceeds(get(ref(callerDb,family+'/members')));
    await assertSucceeds(get(ref(calleeDb,family+'/members')));
    await assertSucceeds(set(ref(callerDb,path),call()));
    await assertSucceeds(set(ref(calleeDb,path+'/status'),status(id1,'accepted')));
    await assertSucceeds(set(ref(callerDb,path+'/offer'),sdp()));
    await assertSucceeds(set(ref(calleeDb,path+'/answer'),sdp()));
    await assertSucceeds(set(ref(callerDb,path+'/ice/senthil/c00'),candidate()));
    await assertSucceeds(set(ref(calleeDb,path+'/status'),status(id1,'ended')));
  }
  const outsider=env.authenticatedContext('outsider',{email_verified:true}).database();
  await assertFails(get(ref(outsider,path)));
  await assertFails(set(ref(outsider,path),call(id2,'outsider','senthil')));
});

test('empty authoritative slots deny access even when legacy membership flags exist',async()=>{
  await env.withSecurityRulesDisabled(ctx=>update(ref(ctx.database(),family),{ownerUid:null,partnerUid:null}));
  for(const uid of ['senthil','shalini','outsider']) {
    await assertFails(get(ref(db(uid),family+'/members')));
    await assertFails(get(at(uid)));
    await assertFails(set(at(uid),call(id1,uid,uid==='senthil'?'shalini':'senthil')));
    await assertFails(set(ref(db(uid),family+'/members/'+uid),true));
  }
});

test('family caller creates, callee accepts, both can end; terminal cannot resurrect',async()=>{
  await assertSucceeds(set(at('senthil'),call()));
  await assertFails(set(at('senthil','status'),status(id1,'accepted')));
  await assertSucceeds(set(at('shalini','status'),status(id1,'accepted')));
  await assertSucceeds(set(at('senthil','status'),status(id1,'ended')));
  await assertFails(set(at('shalini','status'),status(id1,'accepted')));
  await assertSucceeds(set(at('shalini'),call(id2,'shalini','senthil')));
});

test('only authenticated caller may create; cannot call self or outsider or prepopulate payload',async()=>{
  await assertFails(set(at('shalini'),call()));
  await assertFails(set(at('senthil'),call(id1,'senthil','senthil')));
  await assertFails(set(at('senthil'),call(id1,'senthil','outsider')));
  await assertFails(set(at('senthil'),{...call(),offer:sdp()}));
  await assertFails(set(at('senthil'),{...call(),extra:'not allowed'}));
  await assertFails(set(at('senthil'),{...call(),status:status(id1,'accepted')}));
});

test('simultaneous callers cannot replace live singleton; transaction winner is unique',async()=>{
  const results = await Promise.allSettled([
    runTransaction(at('senthil'),value => value === null ? call() : undefined),
    runTransaction(at('shalini'),value => value === null ? call(id2,'shalini','senthil') : undefined)
  ]);
  const winners = results.filter(r => r.status === 'fulfilled' && r.value.committed);
  assert.equal(winners.length,1);
  const current = (await get(at('senthil'))).val();
  await assertFails(set(at(current.caller),call(current.id === id1 ? id2:id1,current.caller,current.callee)));
});

test('cancel and acceptance race never resurrects ended call',async()=>{
  await assertSucceeds(set(at('senthil'),call()));
  await assertSucceeds(set(at('senthil','status'),status(id1,'ended')));
  await assertFails(set(at('shalini','status'),status(id1,'accepted')));
});

test('stale callbacks cannot end, answer, or inject ICE into replacement call',async()=>{
  await accepted(); await assertSucceeds(set(at('senthil','status'),status(id1,'ended')));
  await assertSucceeds(set(at('senthil'),call(id2)));
  await assertSucceeds(set(at('shalini','status'),status(id2,'accepted')));
  await assertSucceeds(set(at('senthil','offer'),sdp(id2)));
  await assertFails(set(at('senthil','status'),status(id1,'ended')));
  await assertFails(set(at('shalini','answer'),sdp(id1)));
  await assertFails(set(at('shalini','ice/shalini/c00'),candidate(id1)));
});

test('only caller offers, callee answers and restart waits for prior answer',async()=>{
  await accepted();
  await assertFails(set(at('shalini','offer'),sdp()));
  await assertFails(set(at('shalini','answer'),sdp()));
  await assertSucceeds(set(at('senthil','offer'),sdp()));
  await assertFails(set(at('senthil','answer'),sdp()));
  await assertFails(set(at('senthil','offer'),sdp(id1,2)));
  await assertSucceeds(set(at('shalini','answer'),sdp()));
  await assertFails(set(at('senthil','offer'),sdp(id1,3)));
  await assertSucceeds(set(at('shalini','restartRequested'),{callId:id1,revision:1}));
  await assertFails(set(at('senthil','restartRequested'),{callId:id1,revision:1}));
  await assertSucceeds(set(at('senthil','offer'),sdp(id1,2)));
  await assertFails(set(at('shalini','answer'),sdp()));
  await assertSucceeds(set(at('shalini','answer'),sdp(id1,2)));
});

test('ICE owned by sender, bounded 64 slots, latest revision only and immutable within revision',async()=>{
  await accepted(); await assertSucceeds(set(at('senthil','offer'),sdp()));
  await assertSucceeds(set(at('senthil','ice/senthil/c00'),candidate()));
  await assertFails(set(at('shalini','ice/senthil/c01'),candidate()));
  await assertFails(set(at('senthil','ice/senthil/c00'),{...candidate(),sdp:'changed'}));
  await assertFails(set(at('senthil','ice/senthil/c64'),candidate()));
  await assertFails(set(at('senthil','ice/senthil/c01'),{...candidate(),sdp:'x'.repeat(2049)}));
  await assertFails(set(at('senthil','ice/senthil/c01'),{...candidate(),line:0.5}));
  await assertSucceeds(set(at('shalini','answer'),sdp()));
  await assertSucceeds(set(at('senthil','offer'),sdp(id1,2)));
  await assertFails(set(at('senthil','ice/senthil/c01'),candidate()));
  await assertSucceeds(set(at('senthil','ice/senthil/c00'),candidate(id1,2)));
});

test('all deletions, metadata mutation and oversized SDP are denied',async()=>{
  await accepted(); await assertSucceeds(set(at('senthil','offer'),sdp()));
  for (const child of ['', 'id','caller','callee','video','createdAt','expiresAt','status','status/value','offer','offer/sdp']) {
    await assertFails(set(at('senthil',child),null));
  }
  await assertFails(update(at('senthil'),{caller:'shalini',callee:'senthil'}));
  await assertFails(set(at('shalini','answer'),{...sdp(),sdp:'x'.repeat(65537)}));
  await assertFails(set(at('shalini','answer'),{...sdp(),extra:true}));
});

test('expired calls reject media signaling and may be replaced; old rings cannot be accepted',async()=>{
  await seedCall(call(id1,'senthil','shalini',{createdAt:Date.now()-60_000}));
  await assertFails(set(at('shalini','status'),status(id1,'accepted')));
  await assertSucceeds(set(at('shalini'),call(id2,'shalini','senthil')));
  await seedCall(call(id1,'senthil','shalini',{createdAt:Date.now()-7_300_000,expiresAt:Date.now()-1000,status:status(id1,'accepted')}));
  await assertFails(set(at('senthil','offer'),sdp()));
  await assertSucceeds(set(at('senthil'),call(id2)));
});

test('lifetime is capped and cannot extend with client updates',async()=>{
  await assertFails(set(at('senthil'),call(id1,'senthil','shalini',{expiresAt:Date.now()+7_210_000})));
  await assertSucceeds(set(at('senthil'),call()));
  await assertFails(set(at('senthil','expiresAt'),Date.now()+7_190_000));
});
