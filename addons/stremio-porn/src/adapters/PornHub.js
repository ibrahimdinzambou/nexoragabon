import HubTrafficAdapter from './HubTrafficAdapter'


class PornHub extends HubTrafficAdapter {
  static DISPLAY_NAME = 'PornHub'
  static ITEMS_PER_PAGE = 30
  static VIDEO_ID_PARAMETER = 'id'
  static REQUEST_HEADERS = {
    'User-Agent': 'Mozilla/5.0',
    Origin: 'https://www.pornhub.com',
  }

  _makeMethodUrl(method) {
    let methodAliases = {
      searchVideos: 'search',
      getVideoById: 'video_by_id',
    }
    return `https://www.pornhub.com/webmasters/${methodAliases[method]}`
  }

  _makeEmbedUrl(id) {
    return `https://www.pornhub.com/embed/${id}`
  }

  async _getStreams(type, id) {
    let embedUrl = this._makeEmbedUrl(id)
    let { body } = await this.httpClient.request(embedUrl)

    let streams = await this._extractMediaDefinitionStreams(body, embedUrl)
    if (!streams.length) {
      streams = this._extractStreamsFromEmbed(body)
    }

    return streams && streams.map((stream) => {
      stream.id = id
      return stream
    })
  }

  async _extractMediaDefinitionStreams(body, embedUrl) {
    let definitions = this._mediaDefinitions(body)
    let streams = []

    for (let definition of definitions) {
      if (definition.remote && definition.videoUrl) {
        let { body: remoteDefinitions } = await this.httpClient.request(definition.videoUrl, {
          json: true,
          headers: {
            referer: embedUrl,
          },
        })
        definitions = definitions.concat(remoteDefinitions || [])
      }
    }

    for (let definition of definitions) {
      let url = definition.videoUrl
      if (!url || definition.remote) {
        continue
      }
      streams.push({
        url,
        headers: {
          ...this.constructor.REQUEST_HEADERS,
          Referer: embedUrl,
        },
        quality: String(definition.quality || '').trim() || undefined,
        title: String(definition.quality || '').trim() || definition.format,
      })
    }

    return streams
      .filter((stream) => stream.url && /\.mp4(?:[?#]|$)/i.test(stream.url))
      .concat(streams.filter((stream) => stream.url && !/\.mp4(?:[?#]|$)/i.test(stream.url)))
  }

  _mediaDefinitions(body) {
    let match = body.match(/"mediaDefinitions"\s*:\s*(\[[\s\S]*?\])\s*,\s*"isVertical"/)
    if (!match || !match[1]) {
      return []
    }
    try {
      return JSON.parse(match[1])
    } catch (error) {
      return []
    }
  }

  _extractStreamsFromEmbed(body) {
    /* eslint-disable max-len */
    // URL example:
    // https:\/\/de.phncdn.com\/videos\/201503\/28\/46795732\/vl_480_493k_46795732.mp4?ttl=1522227092&ri=1228800&rs=696&hash=268b5f4d76927209ef554ac9e93c6c85
    let regexp = /videoUrl["']?\s*:\s*["']?(https?:\\?\/\\?\/[a-z0-9_-]+\.phncdn\.com[^"']+)/gi
    /* eslint-enable max-len */
    let urlMatches = regexp.exec(body)

    if (!urlMatches || !urlMatches[1]) {
      throw new Error('Unable to extract a stream URL from an embed page')
    }

    let url = urlMatches[1]
      .replace(/[\\/]+/g, '/') // Normalize the slashes...
      .replace(/(https?:\/)/, '$1/') // ...but keep the // after "https:"

    if (url[0] === '/') {
      url = `https:/${url}`
    }

    return [{ url }]
  }
}


export default PornHub
