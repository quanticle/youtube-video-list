# youtube-video-list

A small app that, given a YouTube channel URL, will retrieve the video title, upload data, video duration and video link for all videos on that channel, sorted by upload date.

## Installation

- Install [Leiningen](https://leiningen.org/), if you haven't already done so
- Get a [API Key](https://developers.google.com/youtube/registering_an_application)
- `git clone git@github.com:quanticle/youtube-video-list`
- Place the API key in `resources\client-key`
- Build the jar with `lein uberjar`


## Usage

To run:

    $ java -jar youtube-video-list-0.1.0-standalone.jar [-o OUTPUT_TYPE] <youtube_channel_url>

## Options

- `-o OUTPUT_TYPE`
  - Can be one of `screen` or `tsv`
  - If `screen`, outputs video information in single-column format, suitable for a pager, such as `less`
  - If `tsv`, outputs video information in a tab-delimited format, suitable for redirection into a file
  - If omitted, outputs in `screen` format

## Examples

    $ java -jar youtube-video-list-0.1.0-standalone.jar "https://www.youtube.com/c/SpaceX"

### Bugs

I need to handle YouTube URLs of the form `https://www.youtube.com/@<channel>`.

## License

    Copyright (C) 2023  Rohit Patnaik
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>
