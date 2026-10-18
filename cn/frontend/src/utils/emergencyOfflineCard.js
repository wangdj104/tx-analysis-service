// 仅当前页面运行期内的在线授权可支持离线阅读；刷新页面不继承持久化字节的权限。
const runtimeCards = new Map();

/** 只读离线卡仅在GET网络失败时使用；保留原生成时间，身份切换或在线拒绝即清所有权。 */
export function createEmergencyOfflineCard({storage, owner}) {
    let owned = null, epoch = 0;
    const key = value => `offlineEmergencyCard:${value.actorId}:${value.patientId}`;
    const equal = (a, b) => a && b && ['actorId', 'patientId', 'token', 'revision'].every(field => a[field] === b[field]);

    function invalidate() {
        const previous = owned || owner();
        // 先撤销内存所有权；可选存储清理失败不能保留权限或令旧卡重新出现。
        epoch++;
        owned = null;
        if (!previous?.actorId || !previous?.patientId) return;
        const slot = runtimeCards.get(key(previous));
        if (slot) {
            slot.generation++;
            slot.owner = null;
            slot.card = null;
        }
        try { storage?.removeItem(key(previous)); } catch { /* Storage is optional. */ }
    }

    async function load(get) {
        const who = owner();
        if (!who?.actorId || !who?.patientId || !who?.token) {
            invalidate();
            return null;
        }
        if (owned && !equal(owned, who)) invalidate();
        let slot = runtimeCards.get(key(who));
        if (!slot) {
            slot = {generation: 0, owner: null, card: null};
            runtimeCards.set(key(who), slot);
        }
        if (slot.owner && !equal(slot.owner, who)) {
            slot.generation++;
            slot.owner = null;
            slot.card = null;
        }
        owned = {...who};
        const version = ++epoch, generation = slot.generation;
        const same = () => version === epoch && generation === slot.generation && equal(who, owner());
        let value;
        try {
            const response = await get();
            if (!same()) return null;
            if (response?.code && response.code !== 200) throw {status: Number(response.code)};
            value = response?.data ?? response;
            if (!value || typeof value !== 'object' || !value.generatedAt) throw {status: 502};
        } catch (error) {
            if (!same()) return null;
            const onlineStatus = Number(error?.response?.status ?? error?.status ?? error?.code) || 0;
            const network = !onlineStatus && !error?.response && ['ERR_NETWORK', 'ECONNABORTED', 'ETIMEDOUT'].includes(error?.code);
            if (!network) {
                invalidate();
                throw error;
            }
            // 持久化卡从不充当权限证明；只读取本页共享的已授权内存，重挂载也不能复活已拒绝卡。
            return equal(slot.owner, who) && slot.card ? {...slot.card, stale: true} : null;
        }

        // 已获当前在线授权的卡应立即可读；配额或持久化错误不是GET失败。
        slot.owner = {...who};
        slot.card = value;
        try {
            storage?.setItem(key(who), JSON.stringify({
                owner: {actorId: who.actorId, patientId: who.patientId, revision: who.revision},
                card: value
            }));
        } catch { /* The current authorized card remains available in memory. */ }
        return {...value, stale: false};
    }
    return {load, invalidate};
}
