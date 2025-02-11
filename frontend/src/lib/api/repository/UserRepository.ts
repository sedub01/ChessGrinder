import localStorageUtil from "lib/util/LocalStorageUtil";
import {
    BadgeDto,
    ListDto,
    UserDto,
    UserBadgeDto,
    UserHistoryRecordDto,
    UserReputationHistoryRecordDto
} from "lib/api/dto/MainPageData";
import {qualifiedService} from "lib/api/repository/apiSettings";
import restApiClient from "lib/api/RestApiClient";
import authService from "lib/auth/AuthService";
import {ParticipantDto, TournamentPageData} from "../dto/TournamentPageData";

export interface UserRepository {
    getUser(username: string): Promise<UserDto | null>

    getUsers(): Promise<ListDto<UserDto>>

    getMe(): Promise<UserDto | null>

    getHistory(username: string): Promise<ListDto<UserHistoryRecordDto>>

    assignReputation(data: UserReputationHistoryRecordDto): Promise<void>

    updateUser(username: string, user: UserDto): Promise<void>
}

class LocalStorageUserRepository implements UserRepository {
    private userKeyPrefix = "cgd.user";

    async getUser(username: string): Promise<UserDto | null> {
        let userStr = localStorage.getItem(`${this.userKeyPrefix}.${username}`)
        if (!userStr) {
            return null
        }
        let user: UserDto = JSON.parse(userStr);
        user.badges = localStorageUtil.getAllObjectsByPrefix<UserBadgeDto>(`cgd.user__badge.${user.id}.`)
            .map(it => localStorageUtil.getObject<BadgeDto>(`cgd.badge.${it.badgeId}`)!!)
        return user
    }

    async getUsers(): Promise<ListDto<UserDto>> {
        let users: UserDto[] = localStorageUtil.getAllObjectsByPrefix<UserDto>(`${this.userKeyPrefix}.`)
            .map(user => {
                user.badges = localStorageUtil.getAllObjectsByPrefix<UserBadgeDto>(`cgd.user__badge.${user.id}.`)
                    .map(it => localStorageUtil.getObject<BadgeDto>(`cgd.badge.${it.badgeId}`)!!)
                return user
            });
        return {
            values: users
        };
    }

    async getMe(): Promise<UserDto | null> {
        let username = authService.getAuthData()?.username;
        if (username) {
            return await this.getUser(username)
        }
        return null;
    }

    async getHistory(username: string): Promise<ListDto<UserHistoryRecordDto>> {
        let tournaments = localStorageUtil.getAllObjectsByPrefix<TournamentPageData>("cgd.tournament.");
        let result = tournaments
            .map(tournament => {
                let matchingParticipants: ParticipantDto[] = tournament.participants
                    .filter(participant => !!participant.userId)
                    .map(participant => {
                        let user = localStorageUtil.getObject<UserDto>(`cgd.user.${participant.userId!!}`);
                        return [participant, user] as [ParticipantDto, UserDto | null];
                    })
                    .filter(([, user]) => !!user)
                    .filter(([, user]) => {
                        return user!!.username === username || user!!.id === username;
                    })
                    .map(([participant]) => participant);
                let participant = matchingParticipants?.[0];
                return [tournament, participant] as [TournamentPageData, ParticipantDto | undefined];
            })
            .filter(([, participant]) => !!participant)
            .map(([tournament, participant]) => {
                let participantDtos = tournament.participants
                    .sort((a, b) => -(a.score !== b.score ? a.score - b.score : a.buchholz - b.buchholz))
                return {
                    tournament: tournament.tournament,
                    participant: participant,
                    place: participantDtos.indexOf(participant!!) + 1,
                } as UserHistoryRecordDto
            });
        return {
            count: result.length,
            values: result,
        };
    }

    async assignReputation(data: UserReputationHistoryRecordDto): Promise<void> {
        alert("Unsupported");
    }

    async updateUser(username: string, user: UserDto): Promise<void> {
        alert("Unsupported");
    }
}

class RestApiUserRepository implements UserRepository {
    async getUser(username: string): Promise<UserDto | null> {
        return restApiClient.get(`/user/${username}`);
    }

    async getUsers(): Promise<ListDto<UserDto>> {
        return restApiClient.get(`/user`);
    }

    async getMe(): Promise<UserDto | null> {
        return restApiClient.get(`/user/me`);
    }

    async getHistory(username: string): Promise<ListDto<UserHistoryRecordDto>> {
        return restApiClient.get(`/user/${username}/history`);
    }

    async assignReputation(data: UserReputationHistoryRecordDto): Promise<void> {
        return restApiClient.post(`/user/${data.userId}/reputation`, data);
    }

    async updateUser(username: string, user: UserDto): Promise<void> {
        return restApiClient.patch(`/user/${username}`, user);
    }
}

let userRepository = qualifiedService({
    local: new LocalStorageUserRepository(),
    production: new RestApiUserRepository(),
})

export default userRepository;
