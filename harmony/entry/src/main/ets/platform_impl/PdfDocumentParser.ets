// PDF text extraction uses API 12 graphics/text objects. PdfPage.getTextContent
// starts at API 23 and is deliberately not used here.
import { pdfService } from '@kit.PDFKit';
import { Context } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { MAX_INLINE_TEXT_CHARS, newId } from '@amber/chat-domain';
import type { DocumentFileHandle } from '@amber/chat-domain';

export interface PdfTextExtraction {
  text: string;
  truncated: boolean;
}

const extractPdfPath = async (
  path: string, maxCharsPlusOne: number, detectTruncation: boolean,
): Promise<PdfTextExtraction> => {
  if (!canIUse('SystemCapability.OfficeService.PDFService.Core')) {
    return { text: '[ERROR, 当前系统不支持 PDF 文字提取。]', truncated: false };
  }
  const document: pdfService.PdfDocument = new pdfService.PdfDocument();
  try {
    const result: pdfService.ParseResult = document.loadDocument(path);
    if (result === pdfService.ParseResult.PARSE_ERROR_PASSWORD) {
      return { text: '[ERROR, PDF 需要密码，请先解密后重新添加。]', truncated: false };
    }
    if (result !== pdfService.ParseResult.PARSE_SUCCESS) {
      throw new Error('PDF 文件无法解析');
    }
    const fragments: string[] = [];
    let length: number = 0;
    let truncated: boolean = false;
    const pageCount: number = document.getPageCount();
    for (let index: number = 0; index < pageCount && !truncated
      && (length < maxCharsPlusOne || detectTruncation); index++) {
      const page: pdfService.PdfPage = document.getPage(index);
      try {
        const objects: pdfService.GraphicsObject[] = page.getGraphicsObjects();
        let pageHasText: boolean = false;
        for (const object of objects) {
          if (object.type !== pdfService.GraphicsObjectType.OBJECT_TEXT) continue;
          const text: string = (object as pdfService.TextObject).text;
          if (text.trim().length === 0) continue;
          const remaining: number = maxCharsPlusOne - length;
          if (remaining <= 0) {
            truncated = detectTruncation;
            break;
          }
          const separator: string = pageHasText ? '\n' : (length > 0 ? '\n\n' : '');
          const fullFragment: string = separator + text;
          const fragment: string = fullFragment.substring(0, remaining);
          fragments.push(fragment);
          length += fragment.length;
          pageHasText = true;
          // A full raw budget alone is not evidence of lost body text. Probe
          // only until the first nonblank omitted suffix/object/page is found.
          if (detectTruncation && fullFragment.substring(remaining).trim().length > 0) {
            truncated = true;
            break;
          }
        }
      } finally {
        page.release();
      }
      // Release each native page before yielding; long documents must not keep
      // the UI thread occupied across all pages in one turn.
      if (index + 1 < pageCount && !truncated && (length < maxCharsPlusOne || detectTruncation)) {
        await new Promise<void>((resolve: () => void): void => { setTimeout(resolve, 0); });
      }
    }
    const text: string = fragments.join('');
    return { text: text.length > 0 ? text
      : '[ERROR, PDF 没有可提取的文字；扫描件或图片 PDF 需要先进行 OCR。]', truncated };
  } finally {
    document.releaseDocument();
  }
};

export const parsePdfPath = async (
  path: string, maxCharsPlusOne: number = MAX_INLINE_TEXT_CHARS + 1,
): Promise<string> => (await extractPdfPath(path, maxCharsPlusOne, false)).text;

export const parsePdfEntry = (handle: DocumentFileHandle): Promise<string> =>
  parsePdfPath(handle.absolutePath);

// An unsent data: attachment has no native path. Only its temporary preview
// copy is created, then removed; it is never registered as a persisted upload.
const extractPdfPreviewBytes = async (
  bytes: Uint8Array, maxCharsPlusOne: number, detectTruncation: boolean,
): Promise<PdfTextExtraction> => {
  const context: Context = AppStorage.get<Context>('abilityContext') as Context;
  const path: string = `${context.cacheDir}/amber_pdf_preview_${newId()}.pdf`;
  try {
    const file: fileIo.File = fileIo.openSync(path,
      fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
    try {
      fileIo.writeSync(file.fd,
        bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer);
    } finally {
      fileIo.closeSync(file);
    }
    return await extractPdfPath(path, maxCharsPlusOne, detectTruncation);
  } finally {
    if (fileIo.accessSync(path)) fileIo.unlinkSync(path);
  }
};

export const parsePdfPreviewBytes = async (
  bytes: Uint8Array, maxCharsPlusOne: number,
): Promise<string> => (await extractPdfPreviewBytes(bytes, maxCharsPlusOne, false)).text;

export const parsePdfPreviewWithTruncation = (
  bytes: Uint8Array, maxCharsPlusOne: number,
): Promise<PdfTextExtraction> => extractPdfPreviewBytes(bytes, maxCharsPlusOne, true);
