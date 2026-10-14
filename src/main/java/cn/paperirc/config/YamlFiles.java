package cn.paperirc.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Reject duplicate keys before Bukkit conversion can erase the conflicting values. */
public final class YamlFiles {
    private YamlFiles() {}
    public static YamlConfiguration parse(String source) throws Exception {
        LoaderOptions options=new LoaderOptions();options.setAllowDuplicateKeys(false);
        options.setCodePointLimit(8*1024*1024);
        new Yaml(new SafeConstructor(options)).load(source);
        YamlConfiguration result=new YamlConfiguration();result.loadFromString(source);return result;
    }
}
