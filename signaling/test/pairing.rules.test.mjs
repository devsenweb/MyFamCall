import { after, before, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { get, ref, set, update } from 'firebase/database';

// Separate project prevents collisions with the calling-rules suite. Both use the canonical policy.
let env;
const family = 'families/private-family';
const owner = 'owner';
const partner = 'partner';
const outsider = 'outsider';
const nonce = 'a'.repeat(32);
const challenge = 'b'.repeat(32);
const code = '004207';
const db = uid => uid ? env.authenticatedContext(uid).database() : env.unauthenticatedContext().database();
const at = (uid, child = '') => ref(db(uid), family + (child ? '/' + child : ''));
const read = (uid, child) => get(at(uid,child));
function invitation(overrides = {}) {
  const now = Date.now();
  return {nonce,code,createdAt:now,expiresAt:now+290_000,state:'open',...overrides};
}
function request(uid=partner, overrides={}) {
  return {uid,nonce,code,challenge,createdAt:Date.now(),...overrides};
}
function invitationUpdates(inv = invitation()) {
  return {'enrollment/invite':inv,'enrollment/info':{nonce:inv.nonce,expiresAt:inv.expiresAt},
    'enrollment/pending':null,'enrollment/approval':null};
}
function approvalUpdates(req=request()) {
  return {partnerUid:req.uid,[`members/${req.uid}`]:true,'enrollment/invite/state':'used',
    'enrollment/info':null,'enrollment/pending':null,
    'enrollment/approval':{uid:req.uid,nonce:req.nonce,challenge:req.challenge,approvedAt:Date.now()}};
}
async function invite(inv=invitation()) { await assertSucceeds(update(at(owner),invitationUpdates(inv))); return inv; }
async function pending(uid=partner, opts={}) { const req=request(uid,opts); await assertSucceeds(set(at(uid,'enrollment/pending'),req)); return req; }
async function paired() { await invite(); const req=await pending(); await assertSucceeds(update(at(owner),approvalUpdates(req))); return req; }
async function seed(child,value) { await env.withSecurityRulesDisabled(ctx=>set(ref(ctx.database(),family+(child?'/'+child:'')),value)); }
async function adminRead(child='') { let value; await env.withSecurityRulesDisabled(async ctx=>{value=await get(ref(ctx.database(),family+(child?'/'+child:'')));}); return value; }

before(async()=>{
  assert.equal(fs.readFileSync(new URL('../database.rules.json',import.meta.url),'utf8'), fs.readFileSync(new URL('../pairing.database.rules.json',import.meta.url),'utf8'), 'Canonical policy and reviewed pairing artifact must match');
  env=await initializeTestEnvironment({projectId:'demo-my-fam-call-pairing',database:{host:'127.0.0.1',port:Number(process.env.PAIRING_DATABASE_PORT||9000),rules:fs.readFileSync(new URL('../database.rules.json',import.meta.url),'utf8')}});
});
beforeEach(async()=>{await env.clearDatabase();await seed('',{ownerUid:owner,members:{[owner]:true}});});
after(async()=>{if(env)await env.cleanup();});

test('owner role is immutable; self membership can be observed without exposing the family',async()=>{
  assert.equal((await assertSucceeds(read(partner,'ownerUid'))).val(),owner);
  assert.equal((await assertSucceeds(read(partner,`members/${partner}`))).exists(),false);
  assert.equal((await assertSucceeds(read(partner,'enrollment/info'))).exists(),false);
  await assertFails(read(null,'enrollment/info'));
  await assertSucceeds(read(owner,'members'));
  for(const uid of [null,partner,outsider]) {
    await assertFails(get(ref(db(uid),'families')));
    await assertFails(read(uid,''));
    await assertFails(read(uid,'members'));
    await assertFails(read(uid,'call'));
    await assertFails(set(at(uid,'ownerUid'),uid));
    await assertFails(set(at(uid,`members/${uid}`),true));
  }
  await assertFails(set(at(owner,'ownerUid'),'attacker'));
  await assertFails(set(at(owner,'ownerUid'),null));
  await assertFails(set(at(owner,`members/${owner}`),null));
  await assertFails(set(at(owner,''),null));
});

test('only owner can publish a five-minute invite; public metadata reveals no PIN',async()=>{
  for(const uid of [null,partner,outsider])await assertFails(update(at(uid),invitationUpdates()));
  const inv=await invite();
  assert.deepEqual((await assertSucceeds(read(partner,'enrollment/info'))).val(),{nonce,expiresAt:inv.expiresAt});
  assert.equal((await assertSucceeds(read(owner,'enrollment/invite/code'))).val(),code);
  for(const uid of [null,partner,outsider]) {
    for(const path of ['enrollment','enrollment/invite','enrollment/invite/code'])await assertFails(read(uid,path));
  }
  await assertFails(update(at(owner),invitationUpdates(invitation({nonce:'c'.repeat(32),expiresAt:Date.now()+360_000}))));
});

test('wrong codes and malformed writes do not create a pending request or grant membership',async()=>{
  await invite();
  const invalid=[{code:'999999'},{code:4207},{code:'4207'},{nonce:'c'.repeat(32)},{uid:owner},{uid:outsider},
    {challenge:'short'},{challenge:challenge.toUpperCase()},{createdAt:Date.now()+100000},{extra:'forbidden'},
    {createdAt:null},{challenge:null}];
  for(const opts of invalid)await assertFails(set(at(partner,'enrollment/pending'),request(partner,opts)));
  await assertFails(set(at(null,'enrollment/pending'),request()));
  await assertFails(set(at(owner,'enrollment/pending'),request(owner)));
  assert.equal((await adminRead('enrollment/pending')).exists(),false);
  assert.equal((await adminRead('partnerUid')).exists(),false);
});

test('valid code creates only an immutable private pending slot, not calling access',async()=>{
  await invite(); const req=await pending();
  assert.deepEqual((await assertSucceeds(read(partner,'enrollment/pending'))).val(),req);
  await assertSucceeds(read(owner,'enrollment/pending'));
  await assertFails(read(outsider,'enrollment/pending'));
  await assertFails(read(partner,'members'));
  await assertFails(read(partner,'call'));
  await assertFails(set(at(partner,'partnerUid'),partner));
  await assertFails(set(at(partner,`members/${partner}`),true));
  await assertFails(set(at(partner,'enrollment/pending'),request(partner,{challenge:'c'.repeat(32)})));
  await assertFails(set(at(partner,'enrollment/pending/createdAt'),Date.now()));
  await assertFails(set(at(partner,'enrollment/pending'),null));
});

test('owner approval must atomically add exact sole partner, close invite, clear pending, and bind displayed proof',async()=>{
  await invite(); const req=await pending(); const updates=approvalUpdates(req);
  for(const path of Object.keys(updates)) {
    const partial={...updates};delete partial[path];await assertFails(update(at(owner),partial));
  }
  await assertFails(update(at(partner),updates));
  await assertFails(update(at(outsider),updates));
  for(const proof of [{...updates['enrollment/approval'],uid:outsider}, {...updates['enrollment/approval'],nonce:'c'.repeat(32)}, {...updates['enrollment/approval'],challenge:'c'.repeat(32)}]) {
    await assertFails(update(at(owner),{...updates,'enrollment/approval':proof}));
  }
  await assertSucceeds(update(at(owner),updates));
  assert.equal((await read(partner,'partnerUid')).val(),partner);
  assert.deepEqual((await read(partner,'members')).val(),{owner:true,partner:true});
  assert.equal((await adminRead('enrollment/invite/state')).val(),'used');
  assert.equal((await adminRead('enrollment/pending')).exists(),false);
  assert.equal((await adminRead('enrollment/info')).exists(),false);
  await assertFails(read(partner,'enrollment/approval'));
  await assertFails(update(at(owner),updates));
  await assertFails(set(at(outsider,'enrollment/pending'),request(outsider)));
});

test('racing valid requesters produce exactly one bounded candidate and neither becomes a member',async()=>{
  await invite();
  const results=await Promise.allSettled([set(at(partner,'enrollment/pending'),request()),set(at(outsider,'enrollment/pending'),request(outsider,{challenge:'c'.repeat(32)}))]);
  assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
  const req=(await adminRead('enrollment/pending')).val();
  assert.ok([partner,outsider].includes(req.uid));
  assert.equal((await adminRead('partnerUid')).exists(),false);
  assert.deepEqual((await adminRead('members')).val(),{owner:true});
});

test('simultaneous owner approval can commit once and cannot create a second partner',async()=>{
  await invite();const req=await pending();
  const results=await Promise.allSettled([update(at(owner),approvalUpdates(req)),update(at(owner),approvalUpdates(req))]);
  assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
  await assertFails(set(at(owner,`members/${outsider}`),true));
  await assertFails(set(at(owner,'partnerUid'),outsider));
  await assertFails(update(at(owner),invitationUpdates(invitation({nonce:'c'.repeat(32)}))));
});

test('expiry blocks both request and delayed approval, using server-side rules rather than UI time',async()=>{
  await invite();
  await seed('enrollment/invite/expiresAt',Date.now()-1);
  await assertFails(set(at(partner,'enrollment/pending'),request()));
  await assertSucceeds(read(partner,'enrollment/info')); // Metadata is public to signed-in clients; server expiry still blocks writes.
  const future=Date.now()+200000;
  await seed('enrollment/invite/expiresAt',future);
  await seed('enrollment/info/expiresAt',future);
  const req=await pending();
  await seed('enrollment/invite/expiresAt',Date.now()-1);
  await assertFails(update(at(owner),approvalUpdates(req)));
});

test('regeneration invalidates old nonce and stale approvals even when candidate UID is reused',async()=>{
  await invite();const old=await pending();
  const fresh=invitation({nonce:'c'.repeat(32),code:'111222'});
  await assertSucceeds(update(at(owner),invitationUpdates(fresh)));
  await assertFails(set(at(partner,'enrollment/pending'),old));
  const next=await pending(partner,{nonce:fresh.nonce,code:fresh.code,challenge:'d'.repeat(32)});
  await assertFails(update(at(owner),approvalUpdates(old)));
  await assertSucceeds(update(at(owner),approvalUpdates(next)));
});

test('wrong-code flood has bounded zero stored attempts; correct guessed code can occupy slot but cannot enroll',async()=>{
  await invite();
  for(let i=0;i<15;i++)await assertFails(set(at(outsider,'enrollment/pending'),request(outsider,{code:String(i).padStart(6,'0')})));
  assert.equal((await adminRead('enrollment/pending')).exists(),false);
  const attacker=await pending(outsider,{challenge:'c'.repeat(32)});
  await assertFails(set(at(partner,'enrollment/pending'),request())); // Documented DoS, not a protection claim.
  await assertFails(update(at(outsider),approvalUpdates(attacker)));
  await assertFails(read(outsider,'call'));
  const fresh=invitation({nonce:'d'.repeat(32),code:'111222'});
  await assertSucceeds(update(at(owner),invitationUpdates(fresh)));
  const req=await pending(partner,{nonce:fresh.nonce,code:fresh.code});
  await assertSucceeds(update(at(owner),approvalUpdates(req)));
  await assertFails(read(outsider,'call'));
});

test('no partial deletes, expiry extension, third-member insertion, or owner replacement',async()=>{
  await invite();const req=await pending();
  for(const uid of [partner,outsider]) {
    for(const path of ['enrollment','enrollment/info','enrollment/invite','enrollment/pending/challenge','members', 'ownerUid'])await assertFails(set(at(uid,path),null));
  }
  for(const path of ['enrollment/invite/code','enrollment/invite/nonce','enrollment/pending/challenge','enrollment/info'])await assertFails(set(at(owner,path),null));
  await assertFails(set(at(owner,'enrollment/invite/expiresAt'),Date.now()+200000));
  await assertFails(update(at(owner),{...approvalUpdates(req),[`members/${outsider}`]:true}));
  await assertFails(update(at(owner),{...approvalUpdates(req),ownerUid:partner}));
  await assertFails(update(at(owner),{...approvalUpdates(req),[`members/${owner}`]:null}));
});

test('owner cancel removes bounded enrollment and blocks replay without changing existing owner',async()=>{
  await invite();const req=await pending();
  await assertSucceeds(set(at(owner,'enrollment'),null));
  await assertFails(set(at(partner,'enrollment/pending'),req));
  await assertFails(update(at(owner),approvalUpdates(req)));
  assert.deepEqual((await adminRead('members')).val(),{owner:true});
});

test('only owner may revoke with all authoritative and mirrored access removed atomically',async()=>{
  await paired();
  const revoke={partnerUid:null,[`members/${partner}`]:null,enrollment:null};
  for(const uid of [partner,outsider,null])await assertFails(update(at(uid),revoke));
  await assertFails(set(at(owner,'partnerUid'),null));
  await assertFails(set(at(owner,`members/${partner}`),null));
  await assertFails(update(at(owner),{partnerUid:null,[`members/${partner}`]:null}));
  await assertSucceeds(update(at(owner),revoke));
  await assertFails(read(partner,'call'));
  assert.equal((await read(partner,`members/${partner}`)).exists(),false);
  assert.equal((await adminRead('enrollment')).exists(),false);
  await invite(invitation({nonce:'c'.repeat(32)}));
});

test('authoritative slots, not injected legacy membership flags, control call privacy',async()=>{
  await paired();
  await seed(`members/${outsider}`,true);
  await assertFails(read(outsider,'call'));
  await assertFails(read(outsider,'members'));
  await assertSucceeds(read(partner,'call'));
  const now=Date.now();
  const call={id:'11111111-1111-4111-a111-111111111111',caller:owner,callee:partner,createdAt:now,expiresAt:now+60000,video:true,status:{callId:'11111111-1111-4111-a111-111111111111',value:'ringing'}};
  await assertFails(set(at(outsider,'call'),{...call,caller:outsider}));
  await assertFails(set(at(owner,'call'),{...call,callee:outsider}));
  await assertSucceeds(set(at(owner,'call'),call));
  await assertSucceeds(set(at(partner,'call/status'),{callId:call.id,value:'accepted'}));
  await assertSucceeds(set(at(owner,'call/offer'),{callId:call.id,revision:1,sdp:'v=0\r\n'}));
  await assertSucceeds(set(at(partner,'call/answer'),{callId:call.id,revision:1,sdp:'v=0\r\n'}));
  await assertSucceeds(set(at(partner,'call/status'),{callId:call.id,value:'ended'}));
});

test('owner may erase used pairing proof without revoking approved partner',async()=>{
  await paired();await assertSucceeds(set(at(owner,'enrollment'),null));
  await assertSucceeds(read(partner,'call'));
  assert.deepEqual((await read(partner,'members')).val(),{owner:true,partner:true});
});
