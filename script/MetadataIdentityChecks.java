import com.archos.mediascraper.preprocess.ProviderIdentity;
public final class MetadataIdentityChecks {
    static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    public static void main(String[] args){
        check(ProviderIdentity.parse("Film.2025.2160p.mkv").explicit==false,"resolution became an id");
        check(ProviderIdentity.parse("Film [tmdbid-603].mkv").tmdb==603L,"bracket TMDb");
        check(ProviderIdentity.parse("Film {tmdb=603}/movie.mkv").tmdb==603L,"folder TMDb");
        check("tt0133093".equals(ProviderIdentity.parse("Film [imdbid-tt0133093].mkv").imdb),"IMDb tag");
        check(ProviderIdentity.parse("[tmdbid-603]/[tmdbid-604]/file.mkv").tmdb==null,"conflicting IDs accepted");
        check(ProviderIdentity.parse("[tmdbid-0]").explicit&&ProviderIdentity.parse("[tmdbid-0]").tmdb==null,"invalid ID fell through");
        check(ProviderIdentity.parse("[tmdbid-999999999999999999999999]").tmdb==null,"overflow");
        check(ProviderIdentity.parse("lettertt0133093").imdb==null,"embedded token accepted");
        check(ProviderIdentity.parse("[imdbid-tt12345678901]").explicit&&ProviderIdentity.parse("[imdbid-tt12345678901]").imdb==null,"oversized IMDb");
        check(ProviderIdentity.parse("[tmdb-603]/[tmdbid-603]").tmdb==603L,"repeated identical ID");
        System.out.println("PASS: 10 metadata identity behavior checks");
    }
}
