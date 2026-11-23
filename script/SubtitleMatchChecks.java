import com.meedx.providers.SubtitleMatch;

/** Behavior checks for dangerous mismatches and missing evidence, independent of Android. */
public final class SubtitleMatchChecks {
    private static int count;
    private static void require(boolean condition,String name){if(!condition)throw new AssertionError(name);count++;}
    private static SubtitleMatch.Result rank(String a,String b,boolean verified,int season,int episode,Double fps){
        return SubtitleMatch.rank(a,b,verified,season,episode,fps,fps==null?null:25.0,null,null,0,0);
    }
    public static void main(String[] args){
        var exact=rank("Film.2005.1080p.BluRay-GROUP.mkv","Film.2005.1080p.BluRay-GROUP",true,-1,-1,null);
        require(exact.confidence()==SubtitleMatch.Confidence.EXCELLENT,"exact identified release");
        require(rank("Film.mkv","Film",true,-1,-1,null).confidence()!=SubtitleMatch.Confidence.EXCELLENT,"generic title cannot prove edition");
        require(rank("Film.2005.BluRay-GROUP.mkv","Film.2006.BluRay-GROUP",true,-1,-1,null).confidence()==SubtitleMatch.Confidence.UNCERTAIN,"wrong release year");
        require(rank("Show.S01E01.WEB-DL-GROUP.mkv","Show.S01E02.WEB-DL-GROUP",true,-1,-1,null).episodeConflict(),"derive episode from unscripted filename");
        require(rank("Show.S01E02.WEB-DL-GROUP.mkv","Show.S02E02.WEB-DL-GROUP",true,1,2,null).episodeConflict(),"wrong season");
        require(rank("Show.S01E02.WEB-DL-GROUP.mkv","Show.S01E02.WEB-DL-GROUP",true,1,2,null).confidence()==SubtitleMatch.Confidence.EXCELLENT,"correct explicit episode");
        require(rank("Film.2005.BluRay-GROUP.mkv","Film.2005.BluRay-GROUP",false,-1,-1,null).confidence()==SubtitleMatch.Confidence.UNCERTAIN,"identity must be confirmed");
        var timing=rank("Film.2005.BluRay-GROUP.mkv","Film.2005.BluRay-GROUP",true,-1,-1,23.976);
        require(timing.confidence()!=SubtitleMatch.Confidence.EXCELLENT&&timing.confidence()!=SubtitleMatch.Confidence.HIGH,"frame rate conflict caps confidence");
        require(rank("Film.2005.WEB-DL-GROUP.mkv","Film.2005.BluRay-OTHER",true,-1,-1,null).score()<exact.score(),"edition conflict ranks lower");
        require(rank("","",true,-1,-1,null).confidence()==SubtitleMatch.Confidence.UNCERTAIN,"empty release evidence");
        System.out.println("PASS: "+count+" subtitle matching behavior checks");
    }
}
