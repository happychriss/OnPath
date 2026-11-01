// "Share" (the sheet's Share, My routes → ⋮ → Share plan): the plan as a QR code (the OnPath app scans it) and as a link
// – and, from the sheet, Export GPX of the shown plan ([gpx]; the sheet has no ⋯ since 2026-10-31, owner). The plan sits after "#" in
// the link, so it never reaches a server – whoever opens the link plans the same route in their browser or app.
import qrcode from 'qrcode-generator';
import { t } from './i18n';
import { icon } from './icons';

let open: HTMLDialogElement | null = null;

export function shareDialog(link: string, title: string | null, hint?: (box: HTMLElement) => void, gpx?: () => void) {
  open?.close();
  const qr = qrcode(0, 'M');
  qr.addData(link, 'Byte');
  qr.make();
  const d = document.createElement('dialog');
  d.className = 'share';
  d.innerHTML = `
    <h2>${esc(t('web_share'))}</h2>
    <p class="scanline">${esc(t('web_share_scan'))}</p>
    <div class="qr" role="img" aria-label="${esc(t('web_share_qr'))}">${qr.createSvgTag({ scalable: true, margin: 2 })}</div>
    <p class="muted">${esc(t('web_share_text'))}</p>
    <input id="shareLink" readonly aria-label="${esc(t('web_share_link'))}">
    <div class="row">
      <button id="copyLink" class="primary">${esc(t('web_copy_link'))}</button>
      ${'share' in navigator ? `<button id="sysShare">${esc(t('web_share_system'))}</button>` : ''}
      <button id="closeShare">${esc(t('common_close'))}</button>
    </div>
    ${gpx ? `<button id="exportGpx" class="textbtn">${icon('download', 18)}${esc(t('common_export_gpx'))}</button>` : ''}
    <div id="shareStatus" class="status muted"></div>
    <div id="shareHint"></div>`;
  document.body.append(d);
  const input = d.querySelector<HTMLInputElement>('#shareLink')!;
  input.value = link;
  const say = (t: string) => { d.querySelector('#shareStatus')!.textContent = t; };
  d.querySelector<HTMLButtonElement>('#copyLink')!.onclick = async () => {
    try { await navigator.clipboard.writeText(link); say(t('web_link_copied')); }
    catch { input.select(); say(t('web_copy_by_hand')); } // expected: clipboard blocked (frame, http)
  };
  d.querySelector<HTMLButtonElement>('#sysShare')?.addEventListener('click', () => {
    navigator.share({ title: title ?? t('web_name_share'), url: link })
      .catch(() => { /* expected: the user closed the share sheet */ });
  });
  d.querySelector<HTMLButtonElement>('#closeShare')!.onclick = () => d.close();
  d.querySelector<HTMLButtonElement>('#exportGpx')?.addEventListener('click', () => gpx?.());
  d.addEventListener('close', () => { d.remove(); if (open === d) open = null; });
  open = d;
  d.showModal();
  hint?.(d.querySelector<HTMLElement>('#shareHint')!); // the one calm line about the app (apphint.ts)
}

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);
