package cn.paperirc.persistence;

public record PlayerPreference(boolean enabled,String nicknameBase) {
    public static final PlayerPreference DEFAULT=new PlayerPreference(true,null);
}
