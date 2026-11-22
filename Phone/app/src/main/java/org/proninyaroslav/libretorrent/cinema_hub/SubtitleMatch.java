package org.proninyaroslav.libretorrent.cinema_hub;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Evidence-based ordering. Unknown release evidence cannot produce Excellent/High. */
public final class SubtitleMatch {
    private SubtitleMatch(){}
    private static final Pattern EPISODE=Pattern.compile("(?i)s(\\d{1,2})[ ._-]*e(\\d{1,3})");
    private static final Pattern YEAR=Pattern.compile("(?:^|[ ._(-])((?:19|20)\\d{2})(?=$|[ ._)-])");
    private static final Set<String> NOISE=Set.of("mkv","mp4","avi","srt","ass","ssa","vtt","1080p","2160p","720p","x264","x265","h264","h265","hevc","aac","dts","truehd","atmos","hdr","10bit");
    public enum Confidence{EXCELLENT,HIGH,MEDIUM,UNCERTAIN}
    public record Result(int score,Confidence confidence,boolean episodeConflict,String evidence){}
    private static Set<String> tokens(String text){Set<String> result=new HashSet<>();for(String token:normalize(text).split(" "))if(token.length()>1&&!NOISE.contains(token))result.add(token);return result;}
    private static String normalize(String text){return text==null?"":text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim().replaceAll(" +"," ");}
    private static String source(String name){String n=normalize(name);if(n.contains("bluray")||n.contains("blu ray"))return "bluray";if(n.contains("web dl")||n.contains("webdl"))return "webdl";if(n.contains("webrip")||n.contains("web rip"))return "webrip";if(n.contains("hdtv"))return "hdtv";return "";}
    private static String group(String name){if(name==null)return "";String n=name.replaceAll("(?i)\\.(mkv|mp4|srt|ass|ssa|avi)$","");int dash=n.lastIndexOf('-');return dash<0?"":normalize(n.substring(dash+1));}
    public static Result rank(String filename,String release,boolean identityVerified,int expectedSeason,int expectedEpisode,Double videoFps,Double subtitleFps,Long videoDuration,Long subtitleDuration,int goodVotes,int badVotes){
        if(expectedSeason<0||expectedEpisode<0){
            Matcher fromFilename=EPISODE.matcher(filename==null?"":filename);
            if(fromFilename.find()){expectedSeason=Integer.parseInt(fromFilename.group(1));expectedEpisode=Integer.parseInt(fromFilename.group(2));}
        }
        int score=identityVerified?30:0;StringBuilder why=new StringBuilder(identityVerified?"identity; ":"identity unconfirmed; ");
        Matcher videoYear=YEAR.matcher(filename==null?"":filename),releaseYear=YEAR.matcher(release==null?"":release);
        if(videoYear.find()&&releaseYear.find()){
            if(!videoYear.group(1).equals(releaseYear.group(1)))return new Result(-500,Confidence.UNCERTAIN,false,"different release year");
            score+=5;why.append("year; ");
        }
        Matcher episode=EPISODE.matcher(release==null?"":release);
        boolean episodeMatched=false;
        if(expectedSeason>=0&&expectedEpisode>=0){
            boolean any=false;
            while(episode.find()){any=true;if(Integer.parseInt(episode.group(1))==expectedSeason&&Integer.parseInt(episode.group(2))==expectedEpisode)episodeMatched=true;}
            if(any&&!episodeMatched)return new Result(-1000,Confidence.UNCERTAIN,true,"different episode");
            if(episodeMatched){score+=20;why.append("season/episode; ");}else why.append("episode unconfirmed; ");
        }else episodeMatched=true;
        Set<String> a=tokens(filename),b=tokens(release);int common=0;for(String token:a)if(b.contains(token))common++;
        double overlap=a.isEmpty()?0:(double)common/a.size();score+=(int)Math.round(25*overlap);
        boolean exact=!normalize(filename).isEmpty()&&normalize(filename.replaceAll("(?i)\\.(mkv|mp4|avi)$","")).equals(normalize(release==null?"":release.replaceAll("(?i)\\.(srt|ass|ssa)$","")));
        if(exact){score+=30;why.append("exact release; ");}else if(overlap>0)why.append("release tokens; ");
        String sourceA=source(filename),sourceB=source(release);boolean sameSource=!sourceA.isEmpty()&&sourceA.equals(sourceB);
        if(sameSource){score+=10;why.append("source; ");}else if(!sourceA.isEmpty()&&!sourceB.isEmpty()){score-=15;why.append("different source; ");}
        boolean sameGroup=!group(filename).isEmpty()&&group(filename).equals(group(release));if(sameGroup){score+=15;why.append("release group; ");}
        boolean timingConflict=false;
        if(videoFps!=null&&subtitleFps!=null){if(Math.abs(videoFps-subtitleFps)<0.05){score+=8;why.append("frame rate; ");}else{timingConflict=true;score-=20;why.append("frame rate mismatch; ");}}
        if(videoDuration!=null&&subtitleDuration!=null){if(Math.abs(videoDuration-subtitleDuration)<2000){score+=8;why.append("duration; ");}else if(Math.abs(videoDuration-subtitleDuration)>30000){timingConflict=true;score-=15;why.append("duration mismatch; ");}}
        if(goodVotes>=3&&goodVotes>badVotes*3)score+=3;
        if(badVotes>goodVotes&&badVotes>=3)score-=5;
        Confidence confidence;
        if(identityVerified&&episodeMatched&&exact&&(sameGroup||sameSource)&&!timingConflict&&score>=85)confidence=Confidence.EXCELLENT;
        else if(identityVerified&&episodeMatched&&overlap>=0.8&&(sameGroup||sameSource)&&!timingConflict&&score>=70)confidence=Confidence.HIGH;
        else if(identityVerified&&episodeMatched&&overlap>=0.4&&score>=40)confidence=Confidence.MEDIUM;
        else confidence=Confidence.UNCERTAIN;
        return new Result(score,confidence,false,why.toString());
    }
}

