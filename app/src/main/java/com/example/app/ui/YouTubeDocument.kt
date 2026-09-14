package com.example.app.ui

import android.webkit.WebView
import com.example.app.domain.YouTubeLinks

internal fun WebView.loadYouTubeDocument(id: String, positionMs: Long, rate: Float, play: Boolean) {
    require(YouTubeLinks.videoId(id) == id)
    val origin = "https://${context.packageName}"
    loadDataWithBaseURL("$origin/", youTubeDocument(id, positionMs, rate, play, origin), "text/html", "UTF-8", null)
}

internal fun youTubeDocument(id: String, positionMs: Long, rate: Float, play: Boolean, origin: String): String {
    require(YouTubeLinks.videoId(id) == id)
    val start = positionMs.coerceAtLeast(0) / 1000.0
    val speed = rate.coerceIn(0.25f, 2f)
    return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="strict-origin-when-cross-origin">
        <style>html,body{margin:0;padding:0;width:100%;height:100vh;background:#000;overflow:hidden}#player{position:fixed;inset:0;display:block;width:100%;height:100%;border:0}</style></head>
        <body><div id="player"></div><script src="https://www.youtube.com/iframe_api"></script><script>
        var requestedVideo='$id', requestedStart=$start, requestedPlay=$play, requestedRate=$speed, playerReady=false;
        function loadRequestedVideo(id,start){requestedVideo=id;requestedStart=start;requestedPlay=true;if(playerReady){player.loadVideoById(id,start);player.setPlaybackRate(requestedRate);}}
        var player; function reportPlayback(){if(player&&player.getPlayerState&&player.getCurrentTime)Companion.playback(player.getPlayerState(),Math.floor(player.getCurrentTime()),Math.floor(player.getDuration()||0));}
        function onYouTubeIframeAPIReady(){player=new YT.Player('player',{width:'100%',height:'100%',videoId:requestedVideo,playerVars:{controls:1,fs:1,playsinline:1,autoplay:${if (play) 1 else 0},start:$start,origin:'$origin'},events:{
        onReady:function(e){playerReady=true;if(requestedPlay){e.target.loadVideoById(requestedVideo,requestedStart);e.target.playVideo();}else{e.target.cueVideoById(requestedVideo,requestedStart);}e.target.setPlaybackRate(requestedRate);},onStateChange:function(e){reportPlayback();if(e.data===0)Companion.finished();},onError:function(e){Companion.playback(2,0,0);Companion.failed(e.data);}}});}
        setInterval(function(){reportPlayback();if(player&&player.getPlayerState&&player.getPlayerState()===1)Companion.position(Math.floor(player.getCurrentTime()));},1000);
        </script></body></html>
    """.trimIndent()
}
