/**
 * Uploadbefore compressimage (Phoneoriginalchartoften 5–15MB, easytriggergateway 413)
 */
function formatBytes(n) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

export function formatFileSize(n) {
  return formatBytes(n);
}

export function isImageFile(file) {
  if (!file) return false;
  if (file.type?.startsWith('image/')) return true;
  return /\.(jpe?g|png|webp|bmp|heic|heif)$/i.test(file.name || '');
}

/**
 * @param {File} file
 * @param {{ maxWidth?: number, maxHeight?: number, quality?: number, skipBelowBytes?: number }} options
 * @returns {Promise<File>}
 */
export function compressImageFile(file, options = {}) {
  const {
    maxWidth = 1920,
    maxHeight = 1920,
    quality = 0.82,
    skipBelowBytes = 900 * 1024
  } = options;

  if (!isImageFile(file)) return Promise.resolve(file);
  if (file.size <= skipBelowBytes && /^image\/jpe?g$/i.test(file.type || '')) {
    return Promise.resolve(file);
  }

  return new Promise((resolve) => {
    let url, img, settled = false;
    const finish = (result = file) => {
      if (settled) return;
      settled = true;
      if (img) { img.onload = null; img.onerror = null; }
      if (url) URL.revokeObjectURL(url);
      resolve(result);
    };
    try {
      url = URL.createObjectURL(file);
      img = new Image();
      img.onload = () => {
        try {
          let w = img.naturalWidth || img.width;
          let h = img.naturalHeight || img.height;
          const scale = Math.min(1, maxWidth / w, maxHeight / h);
          w = Math.max(1, Math.round(w * scale));
          h = Math.max(1, Math.round(h * scale));

          const canvas = document.createElement('canvas');
          canvas.width = w;
          canvas.height = h;
          const ctx = canvas.getContext('2d');
          if (!ctx) { finish(); return; }
          ctx.drawImage(img, 0, 0, w, h);
          canvas.toBlob((blob) => {
            try {
              if (!blob) { finish(); return; }
              const baseName = (file.name || 'image').replace(/\.[^.]+$/, '');
              const out = new File([blob], `${baseName}.jpg`, {
                type: 'image/jpeg', lastModified: Date.now()
              });
              finish(out.size < file.size ? out : file);
            } catch { finish(); }
          }, 'image/jpeg', quality);
        } catch { finish(); }
      };
      img.onerror = () => finish();
      img.src = url;
    } catch { finish(); }
  });
}

/** @param {File[]} files */
export async function prepareUploadFiles(files) {
  if (!files?.length) return [];
  const out = [];
  for (const f of files) {
    out.push(isImageFile(f) ? await compressImageFile(f) : f);
  }
  return out;
}
