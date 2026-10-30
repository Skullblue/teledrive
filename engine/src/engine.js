// Teledrive engine – talks to Telegram as *you* (MTProto user session, phone-number login). No bot.
// Used by the web UI (window.TGE) and by the Android app (hidden WebView, window.TGE via Kotlin bridge).
import { TelegramClient, Api } from 'telegram';
import { StringSession } from 'telegram/sessions';
import bigInt from 'big-integer';
import { Buffer } from 'buffer';

const PART = 512 * 1024;            // upload part size (Telegram max)
const CHUNK = 2_000_000_000;        // one Telegram message holds up to 2 GB; larger files are split
const TITLE = 'Teledrive Storage';  // private channel created in your account
const SMALL = 10 * 1024 * 1024;     // < 10 MB uses SaveFilePart, otherwise SaveBigFilePart

let client = null, peer = null, loginState = {};
const sleep = ms => new Promise(r => setTimeout(r, ms));
const rnd = () => bigInt.randBetween(bigInt.one, bigInt(2).pow(62));
const uuid = () => (globalThis.crypto && crypto.randomUUID ? crypto.randomUUID() : [...Array(16)].map(() => Math.floor(Math.random() * 256).toString(16).padStart(2, '0')).join(''));

function friendly(e) {
  const m = (e && (e.errorMessage || e.message)) || String(e);
  if (/PHONE_NUMBER_INVALID/.test(m)) return 'That phone number is not valid. Use the international format, e.g. +8801712345678.';
  if (/PHONE_CODE_INVALID/.test(m)) return 'Wrong code, try again.';
  if (/PHONE_CODE_EXPIRED/.test(m)) return 'The code expired. Go back and request a new one.';
  if (/PASSWORD_HASH_INVALID/.test(m)) return 'Wrong two-step-verification password.';
  if (/API_ID_INVALID/.test(m)) return 'Telegram rejected the API ID / API hash. Check them at my.telegram.org.';
  if (/FLOOD/.test(m)) return 'Too many attempts. Wait a bit and try again (' + m + ').';
  if (/PHONE_PASSWORD_FLOOD/.test(m)) return 'Too many attempts. Try again later.';
  return m;
}

async function retry(fn, tries = 4) {
  let last;
  for (let i = 0; i < tries; i++) {
    try { return await fn(); }
    catch (e) {
      last = e; const m = (e && (e.errorMessage || e.message)) || '';
      if (/AUTH_KEY|SESSION_REVOKED|USER_DEACTIVATED|CHANNEL_INVALID|PEER_ID_INVALID/.test(m)) throw e;
      await sleep(1500 * (i + 1));
    }
  }
  throw last;
}

function setPeer(st) {
  peer = st.kind === 'self' ? new Api.InputPeerSelf()
    : new Api.InputPeerChannel({ channelId: bigInt(st.id), accessHash: bigInt(st.hash) });
}

async function uploadBlob({ name, size, read, onProgress }) {
  const total = Math.max(1, Math.ceil(size / PART));
  const big = size > SMALL;
  const fileId = rnd();
  let next = 0, sent = 0;
  const worker = async () => {
    for (;;) {
      const i = next++; if (i >= total) return;
      const off = i * PART, len = Math.min(PART, size - off);
      const bytes = Buffer.from(await read(off, len));
      const ok = await retry(() => big
        ? client.invoke(new Api.upload.SaveBigFilePart({ fileId, filePart: i, fileTotalParts: total, bytes }))
        : client.invoke(new Api.upload.SaveFilePart({ fileId, filePart: i, bytes })));
      if (!ok) throw new Error('Telegram rejected an upload part');
      sent += len; onProgress && onProgress(sent);
    }
  };
  await Promise.all(Array.from({ length: Math.min(4, total) }, worker));
  return big ? new Api.InputFileBig({ id: fileId, parts: total, name })
             : new Api.InputFile({ id: fileId, parts: total, name, md5Checksum: '' });
}

function messageId(res) {
  if (res && res.updates) {
    for (const u of res.updates) if (u instanceof Api.UpdateMessageID) return u.id;
    for (const u of res.updates) if (u.message && u.message.id) return u.message.id;
  }
  if (res && typeof res.id === 'number') return res.id;
  throw new Error('Could not read the new message id');
}

async function sendDoc(file, name, mime, caption) {
  const res = await retry(() => client.invoke(new Api.messages.SendMedia({
    peer, silent: true, message: caption, randomId: rnd(),
    media: new Api.InputMediaUploadedDocument({
      file, mimeType: mime || 'application/octet-stream', forceFile: true,
      attributes: [new Api.DocumentAttributeFilename({ fileName: name })],
    }),
  })));
  return messageId(res);
}

export function mergeIndex(local, remote) {
  const a = local || {}, r = remote || {};
  const dead = new Set([...(a.dead || []), ...(r.dead || [])]);
  const map = new Map();
  for (const f of [...(r.files || []), ...(a.files || [])]) {
    const o = map.get(f.id);
    if (!o || (f.mt || f.ts || 0) >= (o.mt || o.ts || 0)) map.set(f.id, f);
  }
  return {
    v: 1,
    files: [...map.values()].filter(f => !dead.has(f.id)),
    folders: [...new Set([...(a.folders || []), ...(r.folders || [])])].filter(p => !dead.has('dir:' + p)),
    dead: [...dead],
  };
}

async function findIndexMessages() {
  const isIdx = m => m && m.media && typeof m.message === 'string' && m.message.startsWith('#tgdrive-index');
  let list = [];
  try { list = (await client.getMessages(peer, { search: '#tgdrive-index', limit: 10 })).filter(isIdx); } catch {}
  if (!list.length) list = (await client.getMessages(peer, { limit: 200 })).filter(isIdx);   // fallback if search lags
  return list.sort((x, y) => y.id - x.id);
}

export const engine = {
  onStep: null,

  async init({ apiId, apiHash, session }) {
    client = new TelegramClient(new StringSession(session || ''), Number(apiId), apiHash, {
      connectionRetries: 5, useWSS: true, floodSleepThreshold: 600, appVersion: '1.0', deviceModel: 'Teledrive',
    });
    client.setLogLevel('none');
    await client.connect();
    return { authorized: await client.checkAuthorization() };
  },

  /** Starts the phone login. Progress is reported through engine.onStep('code'|'password'|'done'|'error', message). */
  login(phone) {
    const step = (s, m) => engine.onStep && engine.onStep(s, m || '');
    loginState = { err: '' };
    return client.start({
      phoneNumber: async () => phone,
      phoneCode: () => new Promise(res => { loginState.code = res; const m = loginState.err; loginState.err = ''; step('code', m); }),
      password: () => new Promise(res => { loginState.pw = res; const m = loginState.err; loginState.err = ''; step('password', m); }),
      onError: async e => {
        loginState.err = friendly(e);
        const m = (e && (e.errorMessage || e.message)) || '';
        return !/PHONE_CODE_INVALID|PASSWORD_HASH_INVALID/.test(m);   // true = give up, false = ask again
      },
    }).then(() => step('done'), e => { step('error', loginState.err || friendly(e)); });
  },
  submitCode(code) { loginState.code && loginState.code(String(code).trim()); },
  submitPassword(pw) { loginState.pw && loginState.pw(pw); },

  session() { return client.session.save(); },
  async me() {
    const m = await client.getMe();
    return { name: [m.firstName, m.lastName].filter(Boolean).join(' ') || m.username || 'You', phone: m.phone || '', username: m.username || '' };
  },
  async logout() { try { await client.invoke(new Api.auth.LogOut()); } catch {} try { await client.disconnect(); } catch {} },
  async disconnect() { try { await client.disconnect(); } catch {} },

  /** Finds (or creates) the private "Teledrive Storage" channel in your account. Falls back to Saved Messages. */
  async ensureStorage() {
    let st = null;
    const dialogs = await client.getDialogs({ limit: 500 });
    for (const d of dialogs) {
      const e = d.entity;
      if (e instanceof Api.Channel && e.broadcast && !e.username && e.title === TITLE) { st = { kind: 'channel', id: String(e.id), hash: String(e.accessHash), title: TITLE }; break; }
    }
    if (!st) {
      try {
        const res = await client.invoke(new Api.channels.CreateChannel({ broadcast: true, title: TITLE, about: 'Private storage for Teledrive. Do not delete.' }));
        const ch = res.chats.find(c => c instanceof Api.Channel);
        st = { kind: 'channel', id: String(ch.id), hash: String(ch.accessHash), title: TITLE };
      } catch (e) { st = { kind: 'self', title: 'Saved Messages' }; }
    }
    setPeer(st); return st;
  },
  setStorage(st) { setPeer(st); },

  /** spec: {name,size,mime,folder,ts,src,read(offset,len)->Uint8Array|Promise, onProgress(bytesSent)} → index record */
  async upload({ name, size, mime, folder, ts, src, read, onProgress }) {
    const id = uuid(), n = Math.max(1, Math.ceil(size / CHUNK)), chunks = []; let base = 0;
    for (let i = 0; i < n; i++) {
      const off = i * CHUNK, len = Math.min(CHUNK, size - off);
      const pname = n > 1 ? `${name}.part${String(i + 1).padStart(3, '0')}` : name;
      const f = await uploadBlob({ name: pname, size: len, read: (o, l) => read(off + o, l), onProgress: p => onProgress && onProgress(base + p) });
      const mid = await sendDoc(f, pname, n > 1 ? 'application/octet-stream' : mime, `#tgdrive ${id} ${i + 1}/${n}`);
      chunks.push({ mid, size: len }); base += len;
    }
    const now = Date.now();
    return { id, name, size, mime: mime || 'application/octet-stream', folder, ts: ts || now, up: now, mt: now, chunks, src: src || 'web' };
  },
  /** Android: file bytes come from Kotlin through the synchronous Android.readPart bridge. */
  uploadAndroid(h, name, size, mime, folder, ts) {
    return engine.upload({ name, size, mime, folder, ts, src: 'android', read: (o, l) => { const b = Buffer.from(window.Android.readPart(h, o, l), 'base64'); if (b.length !== l) throw new Error('Could not read the file (changed or unavailable)'); return b; }, onProgress: p => window.Android.progress(h, p) });
  },

  /** → { idx: object|null, mids: [message ids of every index copy] }. Throws on network errors (never returns null by mistake). */
  async fetchIndex() {
    const list = await findIndexMessages();
    if (!list.length) return { idx: null, mids: [] };
    const buf = await client.downloadMedia(list[0]);
    return { idx: JSON.parse(Buffer.from(buf).toString('utf8')), mids: list.map(m => m.id) };
  },
  async pushIndex(idx, oldMids) {
    const bytes = Buffer.from(JSON.stringify(idx), 'utf8');
    const f = await uploadBlob({ name: 'tgdrive-index.json', size: bytes.length, read: (o, l) => bytes.subarray(o, o + l) });
    const mid = await sendDoc(f, 'tgdrive-index.json', 'application/json', '#tgdrive-index v1 ' + new Date().toISOString());
    const old = (oldMids || []).filter(x => x !== mid);
    if (old.length) { try { await client.deleteMessages(peer, old, { revoke: true }); } catch {} }
    return mid;
  },
  /** Android: merge new records into the remote index and push it. */
  async syncIndex(newRecs) {
    const rem = await engine.fetchIndex();
    const merged = mergeIndex({ files: newRecs }, rem.idx);
    await engine.pushIndex(merged, rem.mids);
    return merged.files.length;
  },
  merge: mergeIndex,

  /** → array of Uint8Array pieces */
  async download(mid, onProgress, sink) {
    const [m] = await client.getMessages(peer, { ids: [mid] });
    if (!m || !m.media) throw new Error('This file part no longer exists in Telegram');
    const parts = []; let got = 0;
    for await (const ch of client.iterDownload({ file: m.media, requestSize: 1024 * 1024 })) {
      got += ch.length; onProgress && onProgress(got);
      if (sink) await sink(ch); else parts.push(ch);     // sink = stream straight to disk for huge files
    }
    return parts;
  },
  async verifyFilesExist(files) {
    if (!files || !files.length || !peer || !client) return [];
    const midToFile = new Map();
    for (const f of files) {
      const mid = f.chunks && f.chunks[0] && f.chunks[0].mid;
      if (typeof mid === 'number') midToFile.set(mid, f.id);
    }
    const mids = [...midToFile.keys()];
    if (!mids.length) return [];
    const missing = [];
    for (let i = 0; i < mids.length; i += 100) {
      const batch = mids.slice(i, i + 100);
      try {
        const msgs = await client.getMessages(peer, { ids: batch });
        const alive = new Set();
        for (const m of msgs) {
          if (m && m.id && m.media && !(m instanceof Api.MessageEmpty)) {
            alive.add(m.id);
          }
        }
        for (const id of batch) {
          if (!alive.has(id)) {
            const fid = midToFile.get(id);
            if (fid) missing.push(fid);
          }
        }
      } catch (e) {
        console.warn('verifyFilesExist batch check failed', e);
      }
    }
    return missing;
  },
  async deleteMessages(ids) {
    if (!ids || !ids.length || !peer || !client) return;
    try {
      await client.deleteMessages(peer, ids, { revoke: true });
    } catch (e) {
      console.warn('deleteMessages failed in engine:', e);
    }
  },

  async downloadAndroid(h, mid) {
    return engine.download(mid, p => window.Android.progress(h, p), ch => {
      window.Android.writePart(h, Buffer.from(ch).toString('base64'));
    });
  },

  async deleteFileRecord(fileId, mids) {
    if (mids && mids.length) {
      await engine.deleteMessages(mids);
    }
    const rem = await engine.fetchIndex();
    if (rem && rem.idx) {
      const idx = rem.idx;
      idx.files = (idx.files || []).filter(f => f.id !== fileId);
      idx.dead = [...(idx.dead || []), fileId];
      await engine.pushIndex(idx, rem.mids);
      return true;
    }
    return false;
  },

  async refreshAndCleanIndex() {
    const rem = await engine.fetchIndex();
    if (!rem || !rem.idx) return { files: [], folders: [] };
    const files = rem.idx.files || [];
    const missing = await engine.verifyFilesExist(files);
    if (missing && missing.length > 0) {
      const missingSet = new Set(missing);
      rem.idx.files = files.filter(f => !missingSet.has(f.id));
      rem.idx.dead = [...(rem.idx.dead || []), ...missing];
      await engine.pushIndex(rem.idx, rem.mids);
    }
    return rem.idx;
  },

  /** Generic bridge used by the Android WebView. */
  run(id, fn) {
    Promise.resolve().then(fn).then(
      r => window.Android.resolve(id, JSON.stringify(r === undefined ? null : r)),
      e => window.Android.reject(id, String((e && (e.errorMessage || e.message)) || e)));
  },
};

if (!globalThis.TGE) globalThis.TGE = engine;   // a pre-set window.TGE (tests) wins
