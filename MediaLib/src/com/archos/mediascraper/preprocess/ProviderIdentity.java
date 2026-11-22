package com.archos.mediascraper.preprocess;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit filename/folder provider tags outrank fuzzy titles. No network dependency. */
public final class ProviderIdentity {
    public final Long tmdb;
    public final String imdb;
    public final boolean explicit;
    private ProviderIdentity(Long tmdb,String imdb,boolean explicit){this.tmdb=tmdb;this.imdb=imdb;this.explicit=explicit;}
    private static final Pattern TMDB=Pattern.compile("(?i)[\\[{](?:tmdb|tmdbid)[-= :]([0-9]+)[\\]}]");
    private static final Pattern IMDB=Pattern.compile("(?i)(?<![a-z0-9])(tt[0-9]{7,10})(?![0-9])");
    public static ProviderIdentity parse(String path){
        if(path==null)return new ProviderIdentity(null,null,false);
        Matcher tmdb=TMDB.matcher(path),imdb=IMDB.matcher(path);Long id=null;String external=null;boolean explicit=Pattern.compile("(?i)[\\[{](?:tmdb|tmdbid|imdb|imdbid)[-= :]").matcher(path).find();
        while(tmdb.find()){explicit=true;try{long value=Long.parseLong(tmdb.group(1));if(value<=0)return new ProviderIdentity(null,null,true);if(id!=null&&id!=value)return new ProviderIdentity(null,null,true);id=value;}catch(NumberFormatException e){return new ProviderIdentity(null,null,true);}}
        while(imdb.find()){explicit=true;String value=imdb.group(1).toLowerCase(java.util.Locale.US);if(external!=null&&!external.equals(value))return new ProviderIdentity(null,null,true);external=value;}
        return new ProviderIdentity(id,external,explicit);
    }
}
