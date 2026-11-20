// Букмарклет для 5ka.ru: сохраняет точки в chainpoi-pyaterochka.json
//
// Как пользоваться:
//   1. Откройте https://5ka.ru/stores/ и дождитесь карты.
//   2. Выполните этот код (закладка с javascript: или консоль браузера).
//   3. Получится файл chainpoi-pyaterochka.json — его открывает кнопка
//      «Из файла…» в панели плагина.
//
// Данные берутся из того же API, что использует сайт, вашей сессией.
// Плагин ничего не парсит сам и антибот не обходит.

(async () => {
  const bbox = '19,41,180,82'; // вся Россия; можно сузить под свой район

  const resp = await fetch('/api/stores/?bbox=' + bbox, {
    headers: { 'x-requested-with': 'XMLHttpRequest' },
    credentials: 'include'
  });
  if (!resp.ok) {
    alert('5ka: HTTP ' + resp.status);
    return;
  }

  let text = await resp.text();
  text = text.replace(/^\s*callback\((.*)\);?\s*$/s, '$1');
  const json = JSON.parse(text);
  const features = (json.data && json.data.features) || json.features || [];

  const points = features.map(f => {
    const c = f.geometry.coordinates;
    let lon = c[0];
    let lat = c[1];
    if (Math.abs(lat) > 90) {          // на случай [lat, lon]
      const t = lat; lat = lon; lon = t;
    }
    const p = f.properties || {};
    const open = (p.work_start_time || '').slice(0, 5);
    const close = (p.work_end_time || '').slice(0, 5);
    const hours = p.is_24h ? '24/7'
      : (open && close ? 'Mo-Su ' + open + '-' + close : null);

    const tags = {
      shop: 'supermarket',
      name: 'Пятёрочка',
      brand: 'Пятёрочка',
      'brand:wikidata': 'Q1768969'
    };
    if (hours) tags.opening_hours = hours;

    return {
      lat, lon,
      address: p.address || '',
      code: String(f.id || ''),
      tags
    };
  }).filter(p => p.lat && p.lon);

  const blob = new Blob([JSON.stringify({ chain: 'pyaterochka', points })],
    { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'chainpoi-pyaterochka.json';
  a.click();

  alert('Сохранено точек: ' + points.length);
})();
