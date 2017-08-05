package cn.ms.neural.limiter;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.beanutils.BeanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import cn.ms.micro.common.URL;
import cn.ms.micro.extension.SpiMeta;

import com.google.common.io.CharStreams;

/**
 * 限流规则：Map<limiter:[KEY], [SECOND/MINUTE/HOUR/DAY/MONTH/YEAR], [最大资源数量]>
 * 限流统计<limiter:[KEY]:[SECOND/MINUTE/HOUR/DAY/MONTH/YEAR], [当前已被使用的资源数量]>
 * 
 * @author lry
 */
@SpiMeta(name = "redis")
public class RedisLimiter implements Limiter {

	public static final String LIMITER_NAME = "limiter.lua";
	public static final String LIMITER_BATCH_SETRULE_NAME = "limiter_batch_setrule.lua";

	private static final Logger logger = LoggerFactory.getLogger(RedisLimiter.class);

	private static JedisPool jedisPool;
	private String mainScript;
	private String batchSetRuleScript;

	public synchronized JedisPool getJedisPool() {
		return jedisPool;
	}

	@Override
	public boolean start(URL url) {
		try {
			JedisPoolConfig config = new JedisPoolConfig();
			Map<String, String> parameters = url.getParameters();
			BeanUtils.copyProperties(config, parameters);

			jedisPool = new JedisPool(config, url.getHost(), url.getPort());

			mainScript = getScript(LIMITER_NAME);
			batchSetRuleScript = getScript(LIMITER_BATCH_SETRULE_NAME);
		} catch (Exception e) {
			logger.error("The start " + this.getClass().getSimpleName() + " is exception.", e);
		}
		return false;
	}

	// 加载Lua代码
	private String getScript(String name) {
		try {
			Reader reader = null;
			InputStream inputStream = null;
			try {
				inputStream = this.getClass().getClassLoader().getResourceAsStream(name);
				reader = new InputStreamReader(inputStream);
				return CharStreams.toString(reader);
			} finally {
				if (reader != null) {
					reader.close();
				}
			}
		} catch (Exception e) {
			logger.error("The getScript " + this.getClass().getSimpleName() + " is exception.", e);
		}
		
		return "";
	}

	@Override
	public boolean increment(String... keys) {
		return this.increment(null, keys);
	}

	@Override
	public boolean increment(Long expire, String... keys) {
		StringBuffer sb = new StringBuffer();
		for (int i = 0; i < keys.length; i++) {
			sb.append(keys[i]);
			if (i < keys.length - 1) {
				sb.append("/");
			}
		}

		Jedis jedis = null;
		try {
			jedis = this.getJedisPool().getResource();
			List<String> argKeys = Arrays.asList(sb.toString());
			List<String> argValues = new ArrayList<String>();
			if (expire != null) {
				argValues.add(String.valueOf(expire));
			}

			Object resultObject = jedis.eval(mainScript, argKeys, argValues);
			logger.debug("The jedis eval result is: ", resultObject);
			if (resultObject == null || !(resultObject instanceof List<?>)) {
				throw new UnknownError("resultObject=" + resultObject);
			}

			List<?> result = (List<?>) resultObject;
			switch (String.valueOf(result.get(0))) {
			case "OK":
				break;
			case "NORULE":
				break;
			case "FULL":
				break;
			default:
				break;
			}

			return true;
		} catch (Exception e) {
			logger.error("The do increment is exception.", e);
			return false;
		} finally {
			if (jedis != null) {
				jedis.close();
			}
		}
	}
	
	@Override
	public boolean setRule(LimiterStatistics limiterStatistics) {
		Jedis jedis = null;
		try {
			jedis = this.getJedisPool().getResource();
			
			List<String> argKeys = new ArrayList<String>();
			
			List<String> argValues = new ArrayList<String>();
			for (LimiterRes limiterRes:limiterStatistics.getLimiterRes()) {
				argKeys.add(limiterRes.getCategory());
				argValues.add(String.valueOf(limiterRes.getMaxAmount()));
			}
			
			Object result = jedis.eval(batchSetRuleScript, argKeys, argValues);
			return Boolean.valueOf(String.valueOf(result));
		} catch (Exception e) {
			logger.error("The do setRule is exception.", e);
		} finally {
			if (jedis != null) {
				jedis.close();
			}
		}
		
		return false;
	}
	
	@Override
	public List<LimiterStatistics> queryStatistics(String keywords) {
		List<LimiterStatistics> limiterStatistics = new ArrayList<LimiterStatistics>();
		List<LimiterStatistics> list = new ArrayList<LimiterStatistics>();
		Jedis jedis = null;
		try {
			jedis = this.getJedisPool().getResource();
			Set<String> ruleKeys = jedis.keys("rate_limiter_rule:*" + keywords + "*");
			for (String ruleKey:ruleKeys) {
				List<LimiterRes> limiterRes = new ArrayList<LimiterRes>();
				Map<String, String> map = jedis.hgetAll(ruleKey);
				for (Map.Entry<String, String> entry:map.entrySet()) {
					limiterRes.add(new LimiterRes(entry.getKey(), Long.parseLong(entry.getValue()), 0l));
				}
				list.add(new LimiterStatistics(ruleKey.substring("rate_limiter_rule:".length()), limiterRes));
			}
			
			
			for (LimiterStatistics limiterRule:list) {
				List<LimiterRes> limiterRes = new ArrayList<LimiterRes>();
				for (LimiterRes entry:limiterRule.getLimiterRes()) {
					String value = jedis.get("rate_limiter_incr:" + limiterRule.getKeys() + ":"+entry.getCategory());
					limiterRes.add(new LimiterRes(entry.getCategory(), entry.getMaxAmount(), value==null?0:Long.parseLong(value)));
				}
				limiterStatistics.add(new LimiterStatistics(limiterRule.getKeys(), limiterRes));
			}
		} catch (Exception e) {
			logger.error("The do queryStatistics is exception.", e);
		} finally {
			if (jedis != null) {
				jedis.close();
			}
		}

		return limiterStatistics;
	
	}

	@Override
	public void shutdown() {
		if (this.getJedisPool() != null) {
			this.getJedisPool().close();
		}
	}

}