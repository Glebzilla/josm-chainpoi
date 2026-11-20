# Букмарклеты: данные сетей из браузера

Часть сайтов не отдаёт данные обычному HTTP-клиенту: у них JS-челлендж
(5ka.ru — Servicepipe) или антибот (Wildberries — Qrator). Поэтому плагин
не лезет на эти сайты сам, а читает **выгрузку, которую сделал ваш браузер**.

## Формат файла

```json
{
  "chain": "pyaterochka",
  "points": [
    {
      "lat": 45.3677,
      "lon": 40.7093,
      "address": "ул. Ленинградская, 20",
      "code": "77",
      "tags": {
        "shop": "supermarket",
        "name": "Пятёрочка",
        "brand": "Пятёрочка",
        "brand:wikidata": "Q1768969",
        "opening_hours": "Mo-Su 08:00-23:00"
      }
    }
  ]
}
```

`chain` — идентификатор сети: `magnit`, `magnit-kosmetik`, `pyaterochka`,
`ozon`, `ozon-box`, `wildberries`. `tags` — теги, с которыми плагин будет
сравнивать и создавать объекты.

## Какие теги ставить

Сверено с пресетом «POI РФ-РТ» (literan) и name-suggestion-index:

| Сеть | Теги |
|---|---|
| Пятёрочка | `shop=supermarket`, `name=Пятёрочка`, `name:ru=Пятёрочка`, `brand=Пятёрочка`, `brand:wikidata=Q1768969`, `brand:wikipedia=ru:Пятёрочка` |
| Ozon (ПВЗ) | `shop=outpost`, `name=Ozon`, `brand=Ozon`, `brand:wikidata=Q2365235` |
| Ozon box (постамат) | `amenity=parcel_locker`, `name=Ozon box`, `brand=Ozon box`, `brand:wikidata=Q2365235`, `ref=<номер постамата>` |
| Wildberries | `shop=outpost`, `name=Wildberries`, `name:en=Wildberries`, `brand=Wildberries`, `brand:wikidata=Q24933714` |

Контакты (`contact:*`), `wheelchair` и `fitting_room` пресет тоже предлагает,
но это данные сети, а не точки: плагин их не добавляет, чтобы не плодить
одинаковые значения в каждом объекте.

## Пятёрочка

Работает напрямую: плагин сам запрашивает
`rabota5ka.ru/api/vacancy/hire-request` по видимой области. Источник — база
вакансий, поэтому в выборку попадают только магазины, где идёт набор.
Закрытые магазины по этому источнику не определяются.

## Ozon и Wildberries

Готовых букмарклетов пока нет: их карты отдают точки векторными тайлами
(у WB — `wb-maps.wb.ru/api/tiles/poi/*.mvt`), а не JSON-списком. Если пришлёте
образец ответа (DevTools → Network), добавлю разбор. Со стороны плагина всё
готово: профили `ozon`, `ozon-box` и `wildberries` уже есть, файл читается.

## Правовое

Данные берутся с сайта сети для проверки её же магазинов. Из Яндекс.Карт
разрешены только фотографии панорам — подложку и спутник использовать нельзя.
