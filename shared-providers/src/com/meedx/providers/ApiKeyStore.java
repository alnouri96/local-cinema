package com.meedx.providers;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Persisted keys are Keystore-encrypted; plaintext exists only during input, use and import. */
public final class ApiKeyStore {
    private final String alias,prefsName;
    private final Context context;
    public ApiKeyStore(Context c){this(c,"subsource");}
    public ApiKeyStore(Context c,String provider){if(!provider.equals("subsource")&&!provider.equals("omdb"))throw new IllegalArgumentException("Unsupported provider");context=c.getApplicationContext();alias="nova.cinema."+provider+".v1";prefsName=provider+"_secure";}
    private static synchronized SecretKey key(String alias) throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(alias))return (SecretKey)store.getKey(alias,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    public boolean hasSavedKey() {
        return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).contains("ciphertext");
    }
    public synchronized List<String> read() throws Exception {
        var prefs=context.getSharedPreferences(prefsName,Context.MODE_PRIVATE);
        java.util.Map<String,?> snapshot=prefs.getAll();
        String value=(String)snapshot.get("ciphertext");if(value==null)return new ArrayList<>();
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(alias),new GCMParameterSpec(128,Base64.decode((String)snapshot.get("iv"),Base64.NO_WRAP)));
        JSONArray array=new JSONArray(new String(cipher.doFinal(Base64.decode(value,Base64.NO_WRAP)),StandardCharsets.UTF_8));
        ArrayList<String> result=new ArrayList<>();for(int n=0;n<array.length();n++)result.add(array.getString(n));return result;
    }
    public synchronized void save(List<String> values) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(alias));
        byte[] encrypted=cipher.doFinal(new JSONArray(values).toString().getBytes(StandardCharsets.UTF_8));
        boolean saved=context.getSharedPreferences(prefsName,Context.MODE_PRIVATE).edit()
                .putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
                .putString("ciphertext",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit();
        if(!saved)throw new java.io.IOException("Could not persist encrypted keys");
    }
    public static List<String> parse(String text){
        LinkedHashSet<String> keys=new LinkedHashSet<>();
        for(String line:text.replace("\uFEFF","").split("\\s+")){
            String key=line.trim();if(key.matches("[A-Za-z0-9_\\-.:]{16,512}"))keys.add(key);
        }
        return new ArrayList<>(keys);
    }
    public static String masked(String value){return value.length()<10?"••••••••":value.substring(0,4)+"••••••••"+value.substring(value.length()-4);}
}
