import { JsonrpcRequest } from "../base";

/**
 * Sets the charge discharge limiter . (In development)
 *
 * <pre>
 * {
 *   "jsonrpc": "2.0",
 *   "id": UUID,
 *   "method": "setEmergencyReserve",
 *   "params": {
 *     "value": number
 *   }
 * }
 * </pre>
 */
export class SetChargeDischargeLimiterRequest extends JsonrpcRequest {

    private static METHOD: string = "setChargeDischargeLimiter";

    public constructor(
        public override readonly params: {
            value: number
        },
    ) {
        super(SetChargeDischargeLimiterRequest.METHOD, params);
    }

}
