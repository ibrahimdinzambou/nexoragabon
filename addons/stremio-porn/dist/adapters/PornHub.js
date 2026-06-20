"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.default = void 0;

var _HubTrafficAdapter = _interopRequireDefault(require("./HubTrafficAdapter"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _objectSpread(target) { for (var i = 1; i < arguments.length; i++) { var source = arguments[i] != null ? arguments[i] : {}; var ownKeys = Object.keys(source); if (typeof Object.getOwnPropertySymbols === 'function') { ownKeys = ownKeys.concat(Object.getOwnPropertySymbols(source).filter(function (sym) { return Object.getOwnPropertyDescriptor(source, sym).enumerable; })); } ownKeys.forEach(function (key) { _defineProperty(target, key, source[key]); }); } return target; }

function _asyncToGenerator(fn) { return function () { var self = this, args = arguments; return new Promise(function (resolve, reject) { var gen = fn.apply(self, args); function step(key, arg) { try { var info = gen[key](arg); var value = info.value; } catch (error) { reject(error); return; } if (info.done) { resolve(value); } else { Promise.resolve(value).then(_next, _throw); } } function _next(value) { step("next", value); } function _throw(err) { step("throw", err); } _next(); }); }; }

function _defineProperty(obj, key, value) { if (key in obj) { Object.defineProperty(obj, key, { value: value, enumerable: true, configurable: true, writable: true }); } else { obj[key] = value; } return obj; }

class PornHub extends _HubTrafficAdapter.default {
  _makeMethodUrl(method) {
    let methodAliases = {
      searchVideos: 'search',
      getVideoById: 'video_by_id'
    };
    return `https://www.pornhub.com/webmasters/${methodAliases[method]}`;
  }

  _makeEmbedUrl(id) {
    return `https://www.pornhub.com/embed/${id}`;
  }

  _getStreams(type, id) {
    var _this = this;

    return _asyncToGenerator(function* () {
      let embedUrl = _this._makeEmbedUrl(id);

      let {
        body
      } = yield _this.httpClient.request(embedUrl);
      let streams = yield _this._extractMediaDefinitionStreams(body, embedUrl);

      if (!streams.length) {
        streams = _this._extractStreamsFromEmbed(body);
      }

      return streams && streams.map(stream => {
        stream.id = id;
        return stream;
      });
    })();
  }

  _extractMediaDefinitionStreams(body, embedUrl) {
    var _this2 = this;

    return _asyncToGenerator(function* () {
      let definitions = _this2._mediaDefinitions(body);

      let streams = [];

      for (let definition of definitions) {
        if (definition.remote && definition.videoUrl) {
          let {
            body: remoteDefinitions
          } = yield _this2.httpClient.request(definition.videoUrl, {
            json: true,
            headers: {
              referer: embedUrl
            }
          });
          definitions = definitions.concat(remoteDefinitions || []);
        }
      }

      for (let definition of definitions) {
        let url = definition.videoUrl;

        if (!url || definition.remote) {
          continue;
        }

        streams.push({
          url,
          headers: _objectSpread({}, _this2.constructor.REQUEST_HEADERS, {
            Referer: embedUrl
          }),
          quality: String(definition.quality || '').trim() || undefined,
          title: String(definition.quality || '').trim() || definition.format
        });
      }

      return streams.filter(stream => stream.url && /\.mp4(?:[?#]|$)/i.test(stream.url)).concat(streams.filter(stream => stream.url && !/\.mp4(?:[?#]|$)/i.test(stream.url)));
    })();
  }

  _mediaDefinitions(body) {
    let match = body.match(/"mediaDefinitions"\s*:\s*(\[[\s\S]*?\])\s*,\s*"isVertical"/);

    if (!match || !match[1]) {
      return [];
    }

    try {
      return JSON.parse(match[1]);
    } catch (error) {
      return [];
    }
  }

  _extractStreamsFromEmbed(body) {
    /* eslint-disable max-len */
    // URL example:
    // https:\/\/de.phncdn.com\/videos\/201503\/28\/46795732\/vl_480_493k_46795732.mp4?ttl=1522227092&ri=1228800&rs=696&hash=268b5f4d76927209ef554ac9e93c6c85
    let regexp = /videoUrl["']?\s*:\s*["']?(https?:\\?\/\\?\/[a-z0-9_-]+\.phncdn\.com[^"']+)/gi;
    /* eslint-enable max-len */

    let urlMatches = regexp.exec(body);

    if (!urlMatches || !urlMatches[1]) {
      throw new Error('Unable to extract a stream URL from an embed page');
    }

    let url = urlMatches[1].replace(/[\\/]+/g, '/') // Normalize the slashes...
    .replace(/(https?:\/)/, '$1/'); // ...but keep the // after "https:"

    if (url[0] === '/') {
      url = `https:/${url}`;
    }

    return [{
      url
    }];
  }

}

_defineProperty(_defineProperty(_defineProperty(_defineProperty(PornHub, "DISPLAY_NAME", 'PornHub'), "ITEMS_PER_PAGE", 30), "VIDEO_ID_PARAMETER", 'id'), "REQUEST_HEADERS", {
  'User-Agent': 'Mozilla/5.0',
  Origin: 'https://www.pornhub.com'
});

var _default = PornHub;
exports.default = _default;
//# sourceMappingURL=PornHub.js.map