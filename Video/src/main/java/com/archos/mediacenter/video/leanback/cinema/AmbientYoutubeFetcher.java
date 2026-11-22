package com.archos.mediacenter.video.leanback.cinema;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;

final class AmbientYoutubeFetcher {
    record Video(String id, String title, String thumbnail, long seconds) {}
    record Source(String video, String audio, String mime, int height) {}

    static {
        NewPipe.init(new Downloader() {
            private final OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(7, TimeUnit.SECONDS).callTimeout(13, TimeUnit.SECONDS)
                    .cookieJar(okhttp3.CookieJar.NO_COOKIES).build();

            @Override public Response execute(Request request) throws IOException {
                var builder = new okhttp3.Request.Builder().url(request.url());
                request.headers().forEach((name, values) -> values.forEach(value -> builder.addHeader(name, value)));
                byte[] data = request.dataToSend();
                boolean post = "POST".equals(request.httpMethod());
                RequestBody body = data == null && !post ? null
                        : RequestBody.create(data == null ? new byte[0] : data, MediaType.parse("application/json"));
                try (var response = client.newCall(builder.method(request.httpMethod(), body).build()).execute()) {
                    var content = response.body();
                    return new Response(response.code(), response.message(), response.headers().toMultimap(),
                            content == null ? "" : content.string(), response.request().url().toString());
                }
            }
        });
    }

    static String watchUrl(String id) {
        return "https://www.youtube.com/watch?v=" + id;
    }

    static List<Video> search(String query) throws Exception {
        var service = ServiceList.YouTube;
        var extractor = service.getSearchExtractor(query, List.of("videos"), "");
        extractor.fetchPage();
        var result = new ArrayList<Video>();
        var seen = new java.util.HashSet<String>();
        for (var item : SearchInfo.getInfo(extractor).getRelatedItems()) {
            if (!(item instanceof StreamInfoItem video) || video.getDuration() <= 0) continue;
            String id = service.getStreamLHFactory().getId(video.getUrl());
            if (!id.matches("[A-Za-z0-9_-]{11}") || !seen.add(id)) continue;
            var thumbnails = video.getThumbnails();
            String thumbnail = thumbnails.isEmpty() ? "" : thumbnails.get(thumbnails.size() - 1).getUrl();
            result.add(new Video(id, video.getName(), thumbnail, video.getDuration()));
        }
        return result;
    }

    static List<Source> sources(String id, int requestedHeight) throws Exception {
        var info = StreamInfo.getInfo(ServiceList.YouTube, watchUrl(id));
        var result = new ArrayList<Source>();
        if (requestedHeight == 0 && https(info.getHlsUrl()))
            result.add(new Source(info.getHlsUrl(), "", "application/x-mpegURL", 0));
        if (requestedHeight == 0 && https(info.getDashMpdUrl()))
            result.add(new Source(info.getDashMpdUrl(), "", "application/dash+xml", 0));

        var audio = info.getAudioStreams().stream().filter(AmbientYoutubeFetcher::downloadable)
                .filter(stream -> stream.getFormat() == org.schabi.newpipe.extractor.MediaFormat.M4A)
                .findFirst().orElse(null);
        if (audio == null) audio = info.getAudioStreams().stream().filter(AmbientYoutubeFetcher::downloadable)
                .findFirst().orElse(null);
        final String audioUrl = audio == null ? "" : audio.getContent();
        int cap = requestedHeight == 0 ? 2160 : requestedHeight;
        java.util.stream.Stream.concat(info.getVideoStreams().stream(), info.getVideoOnlyStreams().stream())
                .filter(AmbientYoutubeFetcher::downloadable)
                .filter(video -> !video.isVideoOnly() || !audioUrl.isEmpty())
                .filter(video -> height(video) > 0 && height(video) <= cap && supportsDecoder(video))
                .sorted(Comparator.comparingInt(AmbientYoutubeFetcher::height).reversed()
                        .thenComparing(VideoStream::isVideoOnly))
                .forEach(video -> result.add(new Source(video.getContent(), video.isVideoOnly() ? audioUrl : "", null,
                        height(video))));
        if (requestedHeight > 0 && https(info.getHlsUrl()))
            result.add(new Source(info.getHlsUrl(), "", "application/x-mpegURL", 0));
        if (requestedHeight > 0 && https(info.getDashMpdUrl()))
            result.add(new Source(info.getDashMpdUrl(), "", "application/dash+xml", 0));
        return result;
    }

    private static int height(VideoStream video) {
        if (video.getHeight() > 0) return video.getHeight();
        var match = java.util.regex.Pattern.compile("^(\\d+)p").matcher(video.getResolution());
        return match.find() ? Integer.parseInt(match.group(1)) : 0;
    }

    private static boolean supportsDecoder(VideoStream video) {
        String codec = java.util.Objects.toString(video.getCodec(), "");
        String mime = codec.startsWith("av01") ? "video/av01" : codec.startsWith("vp9") || codec.startsWith("vp09")
                ? "video/x-vnd.on2.vp9" : codec.startsWith("avc") ? "video/avc" : "";
        if (mime.isEmpty() || video.getWidth() <= 0 || video.getHeight() <= 0) return true;
        var format = android.media.MediaFormat.createVideoFormat(mime, video.getWidth(), video.getHeight());
        try {
            return new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null;
        } catch (RuntimeException unavailable) {
            return true;
        }
    }

    private static boolean downloadable(Stream stream) {
        return stream.isUrl() && stream.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP && https(stream.getContent());
    }

    private static boolean https(String url) {
        return url != null && url.startsWith("https://");
    }

    private AmbientYoutubeFetcher() {}
}
