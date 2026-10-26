// "Send to phone · Share": the plan as a QR code (the OnPath app scans it) and as a link. The plan sits after "#" in
// the link, so it never reaches a server – whoever opens the link plans the same route in their browser or app.
import qrcode from 'qrcode-generator';

let open: HTMLDialogElement | null = null;

export function shareDialog(link: string, title: string | null) {
  open?.close();
  const qr = qrcode(0, 'M');
  qr.addData(link, 'Byte');
  qr.make();
  const d = document.createElement('dialog');
  d.className = 'share';
  d.innerHTML = `
    <h2>Send to phone · Share</h2>
    <div class="qr" role="img" aria-label="QR code of the plan link">${qr.createSvgTag({ scalable: true, margin: 2 })}</div>
    <p class="muted">Scan this code with the OnPath app on your phone. Anyone with the link can open the plan –
      it is inside the link, nothing is stored on a server.</p>
    <input id="shareLink" readonly aria-label="Plan link">
    <div class="row">
      <button id="copyLink" class="primary">Copy link</button>
      ${'share' in navigator ? '<button id="sysShare">Share…</button>' : ''}
      <button id="closeShare">Close</button>
    </div>
    <div id="shareStatus" class="status muted"></div>`;
  document.body.append(d);
  const input = d.querySelector<HTMLInputElement>('#shareLink')!;
  input.value = link;
  const say = (t: string) => { d.querySelector('#shareStatus')!.textContent = t; };
  d.querySelector<HTMLButtonElement>('#copyLink')!.onclick = async () => {
    try { await navigator.clipboard.writeText(link); say('Link copied.'); }
    catch { input.select(); say('Press Ctrl+C to copy the selected link.'); } // expected: clipboard blocked (frame, http)
  };
  d.querySelector<HTMLButtonElement>('#sysShare')?.addEventListener('click', () => {
    navigator.share({ title: title ?? 'OnPath plan', url: link })
      .catch(() => { /* expected: the user closed the share sheet */ });
  });
  d.querySelector<HTMLButtonElement>('#closeShare')!.onclick = () => d.close();
  d.addEventListener('close', () => { d.remove(); if (open === d) open = null; });
  open = d;
  d.showModal();
}
