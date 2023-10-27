package com.chessgrinder.chessgrinder.chessengine;

import com.chessgrinder.chessgrinder.dto.internals.ScoreModel;
import com.chessgrinder.chessgrinder.dto.MatchDto;
import com.chessgrinder.chessgrinder.dto.ParticipantDto;
import com.chessgrinder.chessgrinder.enums.MatchResult;
import com.google.common.collect.Lists;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Getter
@Data
public class SwissMatchupStrategyImpl implements MatchupStrategy {

    /**
     * "Main" method for swiss service. Takes list of all participants and return list of all matches.
     *
     * @param participants the participants to be paired for new round.
     * @param matchHistory All submitted match results in the whole tournament.
     * @return The list of matches to be played in new round.
     */
    public List<MatchDto> matchUp(List<ParticipantDto> participants, List<MatchDto> matchHistory) {

        List<MatchDto> matches = new ArrayList<>();

        if (participants.isEmpty()) {
            return Collections.emptyList();
        }

        SwissCalculator swissCalculator = new SwissCalculator(participants, matchHistory);

        // Book last player for buy
        {
            List<ParticipantDto> participants1 = swissCalculator.getRemainingParticipants()
                    .stream()
                    .sorted(Comparator.comparing(swissCalculator::hadBuy).reversed())
                    .toList();
            if (participants1.size() % 2 == 1) {
                ParticipantDto last = participants1.get(participants1.size() - 1);
                matches.add(buy(last));
                swissCalculator.book(last);
            }
        }

        for (ParticipantDto participant : swissCalculator.getRemainingParticipants()) {
            if (swissCalculator.isBooked(participant)) {
                continue;
            }

            @Nullable ParticipantDto enemy = findPairForParticipant(swissCalculator.getRemainingParticipants(), participant, matchHistory, swissCalculator);

            MatchDto match = createMatchBetweenTwoParticipants(participant, enemy, swissCalculator);
            matches.add(match);
        }

        matches.sort(Comparator.comparing(it -> MatchResult.BUY.equals(it.getResult()) ? 1 : 0));
        return matches;
    }

    /**
     * Returns secondCandidateForMatch. Finds the closest score gap and selects first player from the second half of group.
     *
     * @param players                all participants in the tournament
     * @param firstCandidateForMatch first candidate for match.
     * @param matchHistory
     * @return
     */
    @Nullable
    public ParticipantDto findPairForParticipant(
            List<ParticipantDto> players,
            ParticipantDto firstCandidateForMatch,
            List<MatchDto> matchHistory,
            SwissCalculator swissCalculator
    ) {

        Set<String> userIdsToExclude = matchHistory.stream()
                .filter(matchDto -> matchDto.getWhite() == null)
                .filter(matchDto -> matchDto.getBlack() == null)
                .filter(matchDto -> matchDto.getWhite().getId().equals(firstCandidateForMatch.getId())
                        || matchDto.getBlack().getId().equals(firstCandidateForMatch.getId()))
                .map(matchDto -> {
                    if (matchDto.getWhite().getId().equals(firstCandidateForMatch.getId())) {
                        return matchDto.getBlack().getId();
                    } else {
                        return matchDto.getWhite().getId();
                    }
                })
                .collect(Collectors.toSet());

        userIdsToExclude.add(firstCandidateForMatch.getId());

        List<ParticipantDto> filteredPlayers = players.stream()
                .filter(player -> !userIdsToExclude.contains(player.getId()))
                .collect(Collectors.toList());

        List<ScoreModel> separateParticipantsByScores = splitIntoChunks(filteredPlayers, swissCalculator);

        ScoreModel scoreModelWithClosestScore = findScoreModelWithClosestScore(separateParticipantsByScores, firstCandidateForMatch);

        if (scoreModelWithClosestScore == null) {
            return null;
        }

        List<ParticipantDto> participants = scoreModelWithClosestScore.getParticipants();
        var split = split(participants);

        if (split.isEmpty()) {
            return null;
        }

        if (split.size() >= 2 && split.get(1) != null && !split.get(1).isEmpty()) {
            return split.get(1).get(0);
        } else if (split.get(0) != null && !split.get(0).isEmpty()) {
            return split.get(0).get(0);
        } else {
            return null;
        }
    }

    /**
     * SecondCandidateForMatch should have the closest score to the firstCandidateForMatch. This method returns ScoreModel
     * which represents list of possible candidates.
     *
     * @param scoreModels
     * @param firstCandidateForMatch
     * @return
     */
    public ScoreModel findScoreModelWithClosestScore(List<ScoreModel> scoreModels, ParticipantDto firstCandidateForMatch) {
        if (scoreModels.isEmpty()) {
            return null;
        }

        BigDecimal targetScore = firstCandidateForMatch.getScore();
        ScoreModel closestScoreModel = scoreModels.get(0); // Initialize with the first ScoreModel

        BigDecimal closestDifference = targetScore.subtract(closestScoreModel.getScore()).abs();

        for (ScoreModel scoreModel : scoreModels) {
            BigDecimal difference = targetScore.subtract(scoreModel.getScore()).abs();

            if (difference.compareTo(closestDifference) < 0) {
                // If the current ScoreModel has a closer score
                closestDifference = difference;
                closestScoreModel = scoreModel;
            }
        }

        return closestScoreModel;
    }

    public static <T> List<List<T>> split(List<T> list) {
        if (list.isEmpty()) {
            return Collections.emptyList();
        }

        if (list.size() == 1) {
            return List.of(list);
        }

        List<List<T>> lists = Lists.partition(list, (list.size() + 1) / 2);
        return List.of(lists.get(0), lists.get(1));
    }


    /**
     * Creates MatchDto object for two participants in the tournament.
     * The player who has played fewer games with the white pieces in the tournament will now be playing as white.
     *
     * @param first  player
     * @param second player
     * @return MatchDto
     */
    public MatchDto createMatchBetweenTwoParticipants(
            ParticipantDto first,
            @Nullable ParticipantDto second,
            SwissCalculator swissCalculator
    ) {
        MatchDto match;

        if (second == null) {
            return buy(first);
        }

        if (swissCalculator.timesPlayedWhite(first) <= swissCalculator.timesPlayedWhite(second)) {
            match = matchOf(first, second);
        } else {
            match = matchOf(second, first);
        }

        swissCalculator.book(first);
        swissCalculator.book(second);

        return match;
    }

    /**
     * Returns list of special ScoreModel objects which groups and sorts all participants by scores.
     *
     * @param participants All players in the tournament.
     * @return List<ScoreModel>
     */
    public static List<ScoreModel> splitIntoChunks(
            List<ParticipantDto> participants,
            SwissCalculator swissCalculator
    ) {

        // Filter participants who have not been booked.
        List<ParticipantDto> notBookedParticipants = participants.stream()
                .filter(player -> !swissCalculator.isBooked(player))
                .toList();


        // Group participants by their scores.
        Map<BigDecimal, List<ParticipantDto>> groupedParticipants = notBookedParticipants.stream()
                .collect(Collectors.groupingBy(
                        ParticipantDto::getScore,
                        Collectors.toList()
                ));

        // Convert the grouped participants into ScoreModel objects.
        List<ScoreModel> scoreModels = new ArrayList<>();
        for (Map.Entry<BigDecimal, List<ParticipantDto>> entry : groupedParticipants.entrySet()) {
            ScoreModel scoreModel = ScoreModel.builder()
                    .score(entry.getKey())
                    .participants(entry.getValue())
                    .build();
            scoreModels.add(scoreModel);
        }

        // Sorting by score.
        scoreModels.sort((a, b) -> b.getScore().compareTo(a.getScore()));

        return scoreModels;
    }

    private static MatchDto matchOf(ParticipantDto first, @Nonnull ParticipantDto second) {
        return MatchDto.builder()
                .white(first)
                .black(second)
                .build();
    }

    private static MatchDto buy(ParticipantDto participant) {
        return MatchDto.builder()
                .black(participant)
                .result(MatchResult.BUY)
                .build();
    }
}


